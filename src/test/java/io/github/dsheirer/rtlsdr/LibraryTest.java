package io.github.dsheirer.rtlsdr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.rtlsdr.internal.Native;
import io.github.dsheirer.rtlsdr.internal.NativeLibraryLoader;
import io.github.dsheirer.libusb.NativeLoader;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Tests that need the native library for the current platform but no hardware. */
class LibraryTest {
    @Test
    void loadsLibraryAndResolvesEveryFunction() {
        assertNotNull(Native.READ_ASYNC);
        // USB may be missing on CI machines, which the library reports as no devices.
        assertTrue(RtlSdr.deviceCount() >= 0);
        assertEquals(RtlSdr.deviceCount(), RtlSdr.devices().size());
    }

    @Test
    void recordsWhichUpstreamWasBuilt() {
        BuildInfo info = RtlSdr.buildInfo();
        assertFalse(info.rtlSdrRef().equals("unknown"), info.toString());
        assertTrue(info.rtlSdrCommit().matches("[0-9a-f]{40}"), info.toString());
    }

    @Test
    void recordsThePatchesApplied() throws IOException {
        List<String> available;
        try (Stream<Path> files = Files.list(Path.of("native", "patches"))) {
            available = files.map(f -> f.getFileName().toString())
                .filter(name -> name.endsWith(".patch")).sorted().toList();
        }
        String pinnedRef = Files.readAllLines(Path.of("native", "versions.env")).stream()
            .filter(line -> line.startsWith("RTLSDR_REF=")).findFirst().orElseThrow()
            .substring("RTLSDR_REF=".length());
        BuildInfo info = RtlSdr.buildInfo();
        // Builds of upstream master may skip a patch that no longer applies.
        if (info.rtlSdrRef().equals(pinnedRef)) {
            assertEquals(available, info.rtlSdrPatches(), info.toString());
        } else {
            assertTrue(available.containsAll(info.rtlSdrPatches()), info.toString());
        }
    }

    @Test
    void namesNoDeviceForAnIndexBeyondTheEnd() {
        assertEquals("", Native.deviceName(RtlSdr.deviceCount() + 10));
    }

    @Test
    void reportsUnknownSerialAsAnError() {
        RtlSdrException e = assertThrows(RtlSdrException.class, () -> RtlSdr.open("no-such-serial-jrtl"));
        assertEquals("rtlsdr_get_index_by_serial", e.function());
        assertTrue(e.code() == -2 || e.code() == -3, e.getMessage());
    }

    @Test
    void reportsOpeningAMissingDeviceAsAnError() {
        RtlSdrException e = assertThrows(RtlSdrException.class, () -> RtlSdr.open(RtlSdr.deviceCount() + 10));
        assertEquals("rtlsdr_open", e.function());
        assertThrows(IllegalArgumentException.class, () -> RtlSdr.open(-1));
    }

    @Test
    void convertsSamplesToFloats() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocateFrom(ValueLayout.JAVA_BYTE, (byte) 0, (byte) 255, (byte) 127);
            SampleBuffer buffer = new SampleBuffer(bytes);
            assertEquals(3, buffer.length());
            assertArrayEquals(new float[] {-1f, 1f, -0.5f / 127.5f}, buffer.toFloats(), 1e-6f);
            byte[] copy = new byte[5];
            buffer.copyTo(copy, 2);
            assertArrayEquals(new byte[] {0, 0, 0, (byte) 255, 127}, copy);
        }
    }

    @Test
    void decodesEnums() {
        assertEquals(Tuner.R828D, Tuner.fromCode(6));
        assertEquals(Tuner.UNKNOWN, Tuner.fromCode(99));
        assertEquals(DirectSampling.Q_BRANCH, DirectSampling.fromCode(2));
        assertThrows(IllegalArgumentException.class, () -> DirectSampling.fromCode(3));
    }

    @Test
    void namesLibraries() {
        assertEquals("librtlsdr.so", NativeLibraryLoader.libraryName("linux-aarch64"));
        assertEquals("librtlsdr.dylib", NativeLibraryLoader.libraryName("macos-x86_64"));
        assertEquals("rtlsdr.dll", NativeLibraryLoader.libraryName("windows-aarch64"));
    }

    @Test
    void sharesLibusbWithJlibusb() throws Throwable {
        // Loading librtlsdr loads jlibusb's libusb, so both sit in its directory.
        assertTrue(RtlSdr.deviceCount() >= 0);
        Path directory = NativeLoader.directory();
        String platform = NativeLoader.platform();
        assertTrue(Files.isRegularFile(directory.resolve(NativeLoader.libusbName(platform))));
        assertTrue(Files.isRegularFile(directory.resolve(NativeLibraryLoader.libraryName(platform))));
        if (platform.startsWith("linux")) {
            // One copy of libusb in the process, the one jlibusb extracted.
            List<String> mapped = Files.readAllLines(Path.of("/proc/self/maps")).stream()
                .filter(line -> line.contains("libusb-1.0"))
                .map(line -> line.substring(line.indexOf('/')))
                .distinct()
                .toList();
            assertEquals(List.of(directory.resolve("libusb-1.0.so.0").toString()), mapped);
        } else if (platform.startsWith("macos")) {
            // dyld must reuse the libusb jlibusb loaded when librtlsdr asks for
            // @loader_path/libusb-1.0.0.dylib, even through the /var symlink.
            List<String> images = dyldImages().stream().filter(name -> name.contains("libusb-1.0")).toList();
            assertEquals(1, images.size(), images.toString());
        }
    }

    /** Paths of the images dyld has loaded into this process. */
    private static List<String> dyldImages() throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup system = linker.defaultLookup();
        MethodHandle count = linker.downcallHandle(system.find("_dyld_image_count").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT));
        MethodHandle name = linker.downcallHandle(system.find("_dyld_get_image_name").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        int images = (int) count.invokeExact();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < images; i++) {
            MemorySegment pointer = (MemorySegment) name.invokeExact(i);
            if (!pointer.equals(MemorySegment.NULL)) {
                names.add(pointer.reinterpret(Long.MAX_VALUE).getString(0));
            }
        }
        return names;
    }
}
