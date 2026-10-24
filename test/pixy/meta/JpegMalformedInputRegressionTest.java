package pixy.meta;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import pixy.meta.adobe.IRB;
import pixy.meta.iptc.IPTC;

/**
 * Regression tests for the JPEG segment parsers and the embedded-metadata parsers (Photoshop IRB,
 * IPTC) hardened in 1.1.3. Each crafted input previously caused an uncaught runtime exception from
 * {@code Metadata.readMetadata} (or while iterating the result); the parser now fails safely.
 */
public class JpegMalformedInputRegressionTest {

	// JPEG marker second bytes (the first is always 0xFF).
	private static final int SOI  = 0xD8;
	private static final int EOI  = 0xD9;
	private static final int APP1 = 0xE1;
	private static final int DHT  = 0xC4;
	private static final int DQT  = 0xDB;
	private static final int SOF0 = 0xC0;
	private static final int SOS  = 0xDA;

	@Test
	@DisplayName("JPEG: a segment length below 2 is rejected with IOException, not NegativeArraySize")
	void segmentLengthBelowTwoRejected() {
		// SOI, then a DHT marker whose length field is 0x0001 (< 2). readSegmentBody must reject it.
		byte[] jpeg = {(byte) 0xFF, (byte) SOI, (byte) 0xFF, (byte) DHT, 0x00, 0x01, (byte) 0xFF, (byte) EOI};
		assertThrows(IOException.class, () -> readMetadata(jpeg));
	}

	@Test
	@DisplayName("JPEG: a truncated DHT segment does not crash with ArrayIndexOutOfBounds")
	void truncatedDhtHandled() {
		// DHT body is a single byte, far too short for the 16-count table it announces.
		byte[] jpeg = jpeg(seg(DHT, new byte[] {0x00}));
		assertDoesNotThrow(() -> readMetadata(jpeg));
	}

	@Test
	@DisplayName("JPEG: a DQT with an invalid precision nibble does not crash")
	void invalidDqtPrecisionHandled() {
		// QT_info 0xF0 -> precision nibble 15 (invalid); previously overflowed the table math / threw.
		byte[] jpeg = jpeg(seg(DQT, new byte[] {(byte) 0xF0}));
		assertDoesNotThrow(() -> readMetadata(jpeg));
	}

	@Test
	@DisplayName("JPEG: an SOF declaring 0xFF components does not crash with NegativeArraySize")
	void sofHugeComponentCountHandled() {
		// precision, height(2), width(2), componentCount = 0xFF, with no component data present.
		byte[] body = {0x08, 0x00, 0x00, 0x00, 0x00, (byte) 0xFF};
		byte[] jpeg = jpeg(seg(SOF0, body));
		assertDoesNotThrow(() -> readMetadata(jpeg));
	}

	@Test
	@DisplayName("JPEG: SOS before any SOF does not crash with IndexOutOfBounds")
	void sosBeforeSofHandled() {
		// SOS with a 1-byte header, then an EOI marker for the scan loop to find.
		byte[] jpeg = {
			(byte) 0xFF, (byte) SOI,
			(byte) 0xFF, (byte) SOS, 0x00, 0x03, 0x00,
			(byte) 0xFF, (byte) EOI
		};
		assertDoesNotThrow(() -> readMetadata(jpeg));
	}

	@Test
	@DisplayName("JPEG: an APP1 segment too short for its identifier is skipped, not fatal")
	void shortApp1SegmentSkipped() {
		// APP1 body is 2 bytes - shorter than the "Exif"/"http://ns.adobe.com/xap/1.0/" identifiers
		// the post-loop code compares against; previously threw StringIndexOutOfBounds.
		byte[] jpeg = jpeg(seg(APP1, new byte[] {0x00, 0x00}));
		assertDoesNotThrow(() -> readMetadata(jpeg));
	}

	@Test
	@DisplayName("IRB: an 8BIM resource whose declared size exceeds the buffer is skipped")
	void irbOversizedResourceSkipped() {
		// "8BIM" + id + Pascal name + a 4-byte size of 1000 with no data following.
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		b.writeBytes("8BIM".getBytes());
		b.writeBytes(new byte[] {0x04, 0x0A});       // resource id
		b.write(0x00);                               // Pascal name length 0 (padded to 1)
		b.write(0x20);                               // the one name byte
		b.writeBytes(new byte[] {0x00, 0x00, 0x03, (byte) 0xE8}); // size = 1000, but no data
		byte[] data = b.toByteArray();
		// Before the fix, ArrayUtils.subArray(data, offset, 1000) threw IllegalArgumentException.
		assertDoesNotThrow(() -> { IRB irb = new IRB(data); irb.ensureDataRead(); });
	}

	@Test
	@DisplayName("IPTC: a dataset whose size exceeds the buffer is skipped, not sliced out of range")
	void iptcOversizedDatasetSkipped() {
		// 0x1c marker, record 2, tag 25, size 1000, with no value bytes present.
		byte[] data = {0x1C, 0x02, 0x19, 0x03, (byte) 0xE8};
		// Before the fix, IPTCDataSet.hashCode -> subArray(data, offset, 1000) threw during de-dup.
		assertDoesNotThrow(() -> { IPTC iptc = new IPTC(data); iptc.ensureDataRead(); });
	}

	// ----------------------------- helpers -----------------------------

	private static void readMetadata(byte[] bytes) throws IOException {
		try (InputStream is = new ByteArrayInputStream(bytes)) {
			Metadata.readMetadata(is);
		}
	}

	/** Wraps the given marker-segment bytes in SOI ... EOI. */
	private static byte[] jpeg(byte[] segment) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0xFF); out.write(SOI);
		out.writeBytes(segment);
		out.write(0xFF); out.write(EOI);
		return out.toByteArray();
	}

	/** Builds one JPEG marker segment: 0xFF, marker, 2-byte big-endian length (= body + 2), body. */
	private static byte[] seg(int marker, byte[] body) {
		int len = body.length + 2;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0xFF);
		out.write(marker);
		out.write((len >>> 8) & 0xFF);
		out.write(len & 0xFF);
		out.writeBytes(body);
		return out.toByteArray();
	}
}
