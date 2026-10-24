package pixy.meta;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.zip.DeflaterOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import pixy.meta.icc.ProfileTagTable;
import pixy.string.XMLUtils;

/**
 * Regression tests for defensive hardening of the metadata parsers against malformed or malicious
 * input. Each test corresponds to one security finding: a crafted input that previously caused an
 * uncaught {@link RuntimeException}, an infinite loop, or unbounded memory use is fed through the
 * public entry points, and the test asserts the parser now fails safely (a checked
 * {@link IOException} or a clean completion) instead.
 *
 * The inputs are built in-memory so the tests need no binary fixtures on disk.
 */
public class MalformedInputRegressionTest {

	private static final byte[] PNG_SIGNATURE = {
		(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
	};

	// ---- Finding 1: TIFF image-file-directory chain cycle -> infinite loop / OOM ----

	@Test
	@DisplayName("TIFF: a self-referencing IFD chain terminates instead of looping forever")
	void tiffCyclicIfdChainTerminates() {
		// Header (II, 42, first IFD at offset 8), one IFD with a single field, and a next-IFD
		// pointer that points back at the same IFD (offset 8) - a cycle.
		ByteBuffer bb = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
		bb.put((byte) 'I').put((byte) 'I');   // little-endian byte order
		bb.putShort((short) 42);              // TIFF magic
		bb.putInt(8);                         // offset to first IFD
		bb.putShort((short) 1);               // one directory entry
		bb.putShort((short) 0x0100);          // tag: ImageWidth
		bb.putShort((short) 3);               // type: SHORT
		bb.putInt(1);                         // count
		bb.putInt(1);                         // inline value
		bb.putInt(8);                         // next IFD -> itself (cycle)
		byte[] tiff = bb.array();

		assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
			assertDoesNotThrow(() -> readMetadata(tiff)),
			"A cyclic IFD chain must not hang or exhaust memory");
	}

	// ---- Finding 2: TIFF field count used to allocate before reading -> OOM / NegativeArraySize ----

	@Test
	@DisplayName("TIFF: an absurd field count is skipped, not allocated")
	void tiffHugeFieldCountIsSkipped() {
		ByteBuffer bb = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
		bb.put((byte) 'I').put((byte) 'I');
		bb.putShort((short) 42);
		bb.putInt(8);
		bb.putShort((short) 1);               // one entry
		bb.putShort((short) 0x0111);          // tag: StripOffsets
		bb.putShort((short) 4);               // type: LONG (4 bytes each)
		bb.putInt(0x7FFFFFFF);                // count ~2 billion -> ~8 GB if allocated
		bb.putInt(0);                         // value offset
		bb.putInt(0);                         // next IFD = none
		byte[] tiff = bb.array();

		assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
			assertDoesNotThrow(() -> readMetadata(tiff)),
			"A huge declared field count must be rejected without a multi-GB allocation");
	}

	// ---- Finding 3: PNG chunk length used directly for allocation ----

	@Test
	@DisplayName("PNG: a negative chunk length is rejected with IOException")
	void pngNegativeChunkLengthRejected() {
		byte[] png = png(chunk(0xFFFFFFFF, "tEXt", new byte[0]));
		// Before the fix this reached new byte[-1] -> NegativeArraySizeException (a RuntimeException).
		assertThrows(IOException.class, () -> readMetadata(png));
	}

	@Test
	@DisplayName("PNG: an oversized chunk length is rejected with IOException")
	void pngOversizedChunkLengthRejected() {
		byte[] png = png(chunk(0x7FFFFFFF, "tEXt", new byte[0]));
		// Before the fix this reached new byte[2^31-1] -> OutOfMemoryError from a tiny file.
		assertThrows(IOException.class, () -> readMetadata(png));
	}

	// ---- Finding 4: PNG compressed-chunk decompression bomb ----

	@Test
	@DisplayName("PNG: an iCCP decompression bomb is capped with IOException, not OOM")
	void pngDecompressionBombCapped() throws IOException {
		// A profile name "x", a null separator, a compression-method byte, then a zlib stream that
		// inflates to ~65 MB of zeros - just over the 64 MB decompressed-size cap.
		byte[] zlib = deflateZeros(65 * 1024 * 1024);
		ByteArrayOutputStream iccp = new ByteArrayOutputStream();
		iccp.write('x');
		iccp.write(0);    // name terminator
		iccp.write(0);    // compression method
		iccp.write(zlib);
		byte[] iccpData = iccp.toByteArray();

		byte[] png = png(chunk(iccpData.length, "iCCP", iccpData));

		IOException ex = assertThrows(IOException.class, () -> readMetadata(png));
		assertTrue(ex.getMessage() != null && ex.getMessage().contains("exceeds"),
			"Expected the decompressed-size cap to fire, got: " + ex.getMessage());
	}

	// ---- Finding 5: GIF sub-block read of -1 at EOF -> new byte[-1] ----

	@Test
	@DisplayName("GIF: truncation inside an extension block does not crash with NegativeArraySize")
	void gifTruncatedExtensionBlockHandled() {
		ByteArrayOutputStream gif = new ByteArrayOutputStream();
		gif.writeBytes("GIF89a".getBytes());        // header (first 4 bytes "GIF8" = magic)
		gif.writeBytes(new byte[] {0, 0, 0, 0, 0, 0, 0}); // logical screen descriptor, no global color table
		gif.write(0x21);                             // extension introducer
		gif.write(0xFE);                             // comment extension
		gif.write(0x03);                             // sub-block length 3
		gif.writeBytes(new byte[] {'a', 'b', 'c'});  // 3 bytes, then the stream ends (no terminator)
		byte[] bytes = gif.toByteArray();

		// Before the fix the EOF (-1) flowed into skipFully/new byte[len] as NegativeArraySizeException.
		assertDoesNotThrow(() -> readMetadata(bytes));
	}

	// ---- Finding 6: BMP palette / data-offset fields used to allocate ----

	@Test
	@DisplayName("BMP: an absurd palette size is rejected with IOException")
	void bmpHugePaletteRejected() {
		byte[] bmp = new byte[54];
		bmp[0] = 'B';
		bmp[1] = 'M';
		putIntLE(bmp, 10, 54);     // data offset
		putIntLE(bmp, 14, 40);     // info header size
		bmp[14 + 14] = 8;          // bits per pixel = 8 (palette image)
		putIntLE(bmp, 14 + 32, 0x40000000); // colorsUsed ~1 billion -> overflow on *4 before the fix
		// Before the fix this reached new byte[numOfColors*4] with an overflowed negative size.
		assertThrows(IOException.class, () -> readMetadata(bmp));
	}

	// ---- Finding 7: ICC tag table count / offset / length ----

	@Test
	@DisplayName("ICC: an absurd tag count is clamped and does not throw")
	void iccHugeTagCountClamped() {
		byte[] data = new byte[140];
		putIntMM(data, 128, 0x7FFFFFFF); // tag count at the tag-table offset
		ProfileTagTable table = new ProfileTagTable();
		assertDoesNotThrow(() -> table.read(data));
		// Only (140 - 132) / 12 = 0 entries can fit, so the count must be clamped down.
		assertTrue(table.getTagCount() == 0,
			"Tag count should be clamped to what the buffer can hold, was " + table.getTagCount());
	}

	@Test
	@DisplayName("ICC: a tag whose data slice is out of range is skipped, not arraycopied")
	void iccOutOfRangeTagSkipped() {
		// 132 header+count bytes + one 12-byte entry = 144 bytes.
		byte[] data = new byte[144];
		putIntMM(data, 128, 1);              // one tag
		putIntMM(data, 132, 0x61616161);     // tag signature
		putIntMM(data, 136, 0x7FFFFFFF);     // data offset far past the buffer
		putIntMM(data, 140, 100);            // data length
		ProfileTagTable table = new ProfileTagTable();
		// Before the fix this reached System.arraycopy with a wild source index -> IndexOutOfBounds.
		assertDoesNotThrow(() -> table.read(data));
	}

	// ---- Finding 8: XML parser hardened against external entities / DOCTYPE ----

	@Test
	@DisplayName("XML: well-formed XMP still parses")
	void xmlBenignStillParses() {
		Document doc = XMLUtils.createXML("<root><child>value</child></root>");
		assertNotNull(doc, "A well-formed document must still parse after hardening");
	}

	@Test
	@DisplayName("XML: a document declaring a DOCTYPE is rejected (XXE protection)")
	void xmlDoctypeRejected() {
		String xxe = "<?xml version=\"1.0\"?>"
			+ "<!DOCTYPE r [<!ENTITY e SYSTEM \"file:///etc/hostname\">]>"
			+ "<r>&e;</r>";
		// With disallow-doctype-decl the parse fails and createXML returns null, so the external
		// entity is never resolved and its content never surfaces as metadata.
		Document doc = XMLUtils.createXML(xxe);
		assertNull(doc, "A DOCTYPE declaration must be rejected to prevent XXE");
	}

	// ---- Finding 9: format-rejection paths throw IOException, not RuntimeException ----

	@Test
	@DisplayName("A file shorter than the 4-byte magic number is rejected with IOException")
	void tinyFileRejectedWithIOException() {
		// Previously PeekHeadInputStream wrapped the EOF in an unchecked RuntimeException.
		assertThrows(IOException.class, () -> readMetadata(new byte[] {0x01, 0x02}));
	}

	@Test
	@DisplayName("A file with the PNG magic but a corrupt signature is rejected with IOException")
	void pngBadSignatureRejectedWithIOException() {
		// First 4 bytes match the PNG magic (so it is routed to the PNG reader), but the full 8-byte
		// signature is wrong. Previously this threw a RuntimeException.
		byte[] bytes = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x00};
		assertThrows(IOException.class, () -> readMetadata(bytes));
	}

	// ----------------------------- helpers -----------------------------

	private static void readMetadata(byte[] bytes) throws IOException {
		try (InputStream is = new ByteArrayInputStream(bytes)) {
			Metadata.readMetadata(is);
		}
	}

	/** Builds a PNG: signature, a valid 13-byte IHDR, the given body chunk bytes, then IEND. */
	private static byte[] png(byte[] bodyChunk) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(PNG_SIGNATURE);
		out.writeBytes(chunk(13, "IHDR", new byte[13]));
		out.writeBytes(bodyChunk);
		out.writeBytes(chunk(0, "IEND", new byte[0]));
		return out.toByteArray();
	}

	/**
	 * Encodes one PNG chunk: a big-endian length, the 4-byte type, the data, and a (zeroed, unchecked)
	 * CRC. The declared length may deliberately differ from {@code data.length} to exercise the guard.
	 */
	private static byte[] chunk(int declaredLength, String type, byte[] data) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(intMM(declaredLength));
		out.writeBytes(type.getBytes());
		out.writeBytes(data);
		out.writeBytes(new byte[4]); // CRC placeholder (not verified by the reader)
		return out.toByteArray();
	}

	private static byte[] intMM(int v) {
		return new byte[] {
			(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v
		};
	}

	private static void putIntLE(byte[] buf, int off, int v) {
		buf[off] = (byte) v;
		buf[off + 1] = (byte) (v >>> 8);
		buf[off + 2] = (byte) (v >>> 16);
		buf[off + 3] = (byte) (v >>> 24);
	}

	private static void putIntMM(byte[] buf, int off, int v) {
		buf[off] = (byte) (v >>> 24);
		buf[off + 1] = (byte) (v >>> 16);
		buf[off + 2] = (byte) (v >>> 8);
		buf[off + 3] = (byte) v;
	}

	/** Returns a zlib stream that inflates to {@code size} zero bytes, written without holding it all. */
	private static byte[] deflateZeros(int size) throws IOException {
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		try (DeflaterOutputStream dos = new DeflaterOutputStream(compressed)) {
			byte[] block = new byte[1024 * 1024];
			int remaining = size;
			while (remaining > 0) {
				int n = Math.min(block.length, remaining);
				dos.write(block, 0, n);
				remaining -= n;
			}
		}
		return compressed.toByteArray();
	}
}
