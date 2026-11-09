package pixy.meta;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pixy.meta.CrossValidationTest.read;
import static pixy.meta.CrossValidationTest.transform;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.drew.metadata.Directory;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.icc.IccDirectory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.jpeg.JpegCommentDirectory;
import com.drew.metadata.xmp.XmpDirectory;

import pixy.image.tiff.TiffTag;
import pixy.meta.exif.Exif;
import pixy.meta.exif.ExifTag;
import pixy.meta.iptc.IPTC;
import pixy.meta.iptc.IPTCApplicationTag;
import pixy.meta.iptc.IPTCDataSet;
import pixy.meta.iptc.IPTCEnvelopeTag;
import pixy.meta.iptc.IPTCRecord;
import pixy.meta.jpeg.JpegExif;
import pixy.meta.tiff.TiffExif;

/**
 * {@link Metadata#insertMetadata}: one call, one pass for JPEG, and the IPTC character set
 * declaration written with every IPTC block.
 */
public class MetadataUpdateTest {

	private static final String XMP = "<x:xmpmeta xmlns:x='adobe:ns:meta/'>"
			+ "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
			+ "<rdf:Description rdf:about='' xmlns:xmp='http://ns.adobe.com/xap/1.0/' xmp:CreatorTool='pixymeta-update'/>"
			+ "</rdf:RDF></x:xmpmeta>";

	// ---- single pass JPEG ----

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"images/1.jpg", "images/12.jpg", "images/sea.jpg"})
	@DisplayName("JPEG: EXIF, IPTC, XMP, ICC and a comment inserted in one pass read back")
	void jpegAllInOnePass(String image) throws Exception {
		byte[] original = read(image);
		Exif exif = new JpegExif();
		exif.addField(TiffTag.ARTIST, "Jane Tester");
		exif.addField(ExifTag.FNUMBER, 2.8);
		byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
		MetadataUpdate update = new MetadataUpdate()
				.exif(exif, true)
				.iptc(List.of(new IPTCDataSet(IPTCApplicationTag.OBJECT_NAME, "Title"),
						new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "new keyword")), true)
				.xmp(XMP)
				.iccProfile(icc)
				.comment("one pass");

		byte[] out = transform(original, (is, os) -> Metadata.insertMetadata(is, os, update));

		com.drew.metadata.Metadata before = MetadataExtractorOracle.read(original);
		com.drew.metadata.Metadata me = MetadataExtractorOracle.read(out);
		ExifIFD0Directory ifd0 = me.getFirstDirectoryOfType(ExifIFD0Directory.class);
		ExifSubIFDDirectory sub = me.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
		assertEquals("Jane Tester", ifd0.getString(ExifIFD0Directory.TAG_ARTIST));
		assertEquals(2.8, sub.getDouble(ExifSubIFDDirectory.TAG_FNUMBER), 1e-9);
		// Existing EXIF (camera make, maker note) survives the merge
		ExifIFD0Directory ifd0Before = before.getFirstDirectoryOfType(ExifIFD0Directory.class);
		if (ifd0Before != null && ifd0Before.containsTag(ExifIFD0Directory.TAG_MAKE))
			assertEquals(ifd0Before.getString(ExifIFD0Directory.TAG_MAKE), ifd0.getString(ExifIFD0Directory.TAG_MAKE));
		assertEquals(makerNote(before) != null, makerNote(me) != null, "maker note presence");
		IptcDirectory iptc = me.getFirstDirectoryOfType(IptcDirectory.class);
		assertEquals("Title", iptc.getString(IptcDirectory.TAG_OBJECT_NAME));
		assertTrue(iptc.getKeywords().contains("new keyword"));
		IptcDirectory iptcBefore = before.getFirstDirectoryOfType(IptcDirectory.class);
		if (iptcBefore != null && iptcBefore.getKeywords() != null)
			assertTrue(iptc.getKeywords().containsAll(iptcBefore.getKeywords()), "existing keywords kept");
		assertEquals("pixymeta-update", me.getFirstDirectoryOfType(XmpDirectory.class).getXmpProperties().get("xmp:CreatorTool"));
		assertEquals(icc.length, me.getFirstDirectoryOfType(IccDirectory.class).getInt(IccDirectory.TAG_PROFILE_BYTE_COUNT));
		assertEquals("one pass", me.getFirstDirectoryOfType(JpegCommentDirectory.class).getString(JpegCommentDirectory.TAG_COMMENT));
		assertEquals(List.of(), MetadataExtractorOracle.errors(me));
		assertSameScanData(original, out);
	}

	@Test
	@DisplayName("JPEG: one-pass result carries the same metadata as the chained single operations")
	void jpegOnePassEqualsChain() throws Exception {
		byte[] original = read("images/sea.jpg");
		byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
		List<IPTCDataSet> iptc = List.of(new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "k"));

		byte[] onePass = transform(original, (is, os) -> Metadata.insertMetadata(is, os, new MetadataUpdate()
				.exif(artist(), true).iptc(iptc, true).xmp(XMP).iccProfile(icc).comment("c")));

		byte[] chained = transform(original, (is, os) -> Metadata.insertExif(is, os, artist(), true));
		chained = transform(chained, (is, os) -> Metadata.insertIPTC(is, os, iptc, true));
		chained = transform(chained, (is, os) -> Metadata.insertXMP(is, os, XMP));
		chained = transform(chained, (is, os) -> Metadata.insertICCProfile(is, os, icc));
		chained = transform(chained, (is, os) -> Metadata.insertComment(is, os, "c"));

		assertEquals(describe(MetadataExtractorOracle.read(chained)), describe(MetadataExtractorOracle.read(onePass)));
	}

	@Test
	@DisplayName("JPEG: removals and insertions in the same pass")
	void jpegRemoveAndInsert() throws Exception {
		byte[] original = read("images/12.jpg"); // EXIF, IPTC, ICC, XMP, IRB, Adobe
		com.drew.metadata.Metadata before = MetadataExtractorOracle.read(original);
		assertNotNull(before.getFirstDirectoryOfType(XmpDirectory.class));
		assertNotNull(before.getFirstDirectoryOfType(IccDirectory.class));

		byte[] out = transform(original, (is, os) -> Metadata.insertMetadata(is, os, new MetadataUpdate()
				.remove(MetadataType.XMP, MetadataType.ICC_PROFILE, MetadataType.IPTC, MetadataType.JPG_ADOBE)
				.exif(artist(), false)));

		com.drew.metadata.Metadata me = MetadataExtractorOracle.read(out);
		assertNull(me.getFirstDirectoryOfType(XmpDirectory.class));
		assertNull(me.getFirstDirectoryOfType(IccDirectory.class));
		assertNull(me.getFirstDirectoryOfType(IptcDirectory.class));
		assertNull(MetadataExtractorOracle.directory(me, "Adobe JPEG"));
		ExifIFD0Directory ifd0 = me.getFirstDirectoryOfType(ExifIFD0Directory.class);
		assertEquals("Jane Tester", ifd0.getString(ExifIFD0Directory.TAG_ARTIST));
		assertFalse(ifd0.containsTag(ExifIFD0Directory.TAG_MAKE), "EXIF was replaced, not merged");
		// The other Photoshop resources are still there even though IPTC was removed from the IRB
		Map<MetadataType, Metadata> pixy = Metadata.readMetadata(new ByteArrayInputStream(out));
		assertTrue(pixy.containsKey(MetadataType.PHOTOSHOP_IRB));
		assertFalse(pixy.containsKey(MetadataType.IPTC));
		assertSameScanData(original, out);
		assertSamePixels(original, out);
	}

	@Test
	@DisplayName("JPEG: an empty update and an update of unrelated types copy the image unchanged")
	void jpegNoChange() throws Exception {
		byte[] original = read("images/12.jpg");
		assertArrayEquals(original, transform(original, (is, os) -> Metadata.insertMetadata(is, os, new MetadataUpdate())));
		// Removing a type the image does not have rewrites the segments but changes nothing
		byte[] out = transform(original, (is, os) -> Metadata.insertMetadata(is, os, new MetadataUpdate().remove(MetadataType.COMMENT)));
		assertEquals(describe(MetadataExtractorOracle.read(original)), describe(MetadataExtractorOracle.read(out)));
	}

	@Test
	@DisplayName("JPEG: segments the update does not touch stay where they were")
	void jpegUntouchedSegmentsKeepOrder() throws Exception {
		byte[] original = read("images/12.jpg");
		byte[] out = transform(original, (is, os) -> Metadata.insertMetadata(is, os, new MetadataUpdate().xmp(XMP)));
		List<String> before = markers(original);
		List<String> after = markers(out);
		before.remove("APP1-XMP");
		after.remove("APP1-XMP");
		assertEquals(before, after);
		// and the new XMP sits right after the EXIF APP1, as the EXIF/XMP specifications want
		List<String> all = markers(out);
		assertEquals(all.indexOf("APP1-EXIF") + 1, all.indexOf("APP1-XMP"));
	}

	// ---- chained formats ----

	@Test
	@DisplayName("TIFF: the update is applied as a chain and reads back")
	void tiffChained() throws Exception {
		byte[] original = read("images/f1.tif");
		TiffExif exif = new TiffExif();
		exif.addField(TiffTag.ARTIST, "Jane Tester");
		byte[] out = transform(original, (is, os) -> Metadata.insertMetadata(is, os, new MetadataUpdate()
				.exif(exif, true)
				.iptc(List.of(new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "tiff")), false)
				.xmp(XMP)));
		com.drew.metadata.Metadata me = MetadataExtractorOracle.read(out);
		assertEquals("Jane Tester", me.getFirstDirectoryOfType(ExifIFD0Directory.class).getString(ExifIFD0Directory.TAG_ARTIST));
		assertEquals(List.of("tiff"), me.getFirstDirectoryOfType(IptcDirectory.class).getKeywords());
		assertEquals("pixymeta-update", me.getFirstDirectoryOfType(XmpDirectory.class).getXmpProperties().get("xmp:CreatorTool"));
	}

	@Test
	@DisplayName("PNG: XMP and ICC are inserted, IPTC (unsupported) is skipped with a log message")
	void pngChained() throws Exception {
		byte[] original = read("images/stonehouse.png");
		byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
		byte[] out = transform(original, (is, os) -> Metadata.insertMetadata(is, os, new MetadataUpdate()
				.xmp(XMP).iccProfile(icc).iptc(List.of(new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "png")), false)));
		com.drew.metadata.Metadata me = MetadataExtractorOracle.read(out);
		assertEquals("pixymeta-update", me.getFirstDirectoryOfType(XmpDirectory.class).getXmpProperties().get("xmp:CreatorTool"));
		assertNotNull(me.getFirstDirectoryOfType(IccDirectory.class));
		assertNull(me.getFirstDirectoryOfType(IptcDirectory.class));
	}

	@Test
	@DisplayName("BMP: formats without metadata support are copied unchanged")
	void bmpUnchanged() throws Exception {
		byte[] original = read("images/niagra_falls.bmp");
		assertArrayEquals(original, transform(original, (is, os) -> Metadata.insertMetadata(is, os, new MetadataUpdate().xmp(XMP))));
	}

	// ---- IPTC character set ----

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"images/1.jpg", "images/wizard.jpg", "images/f1.tif", "images/iptc.tif"})
	@DisplayName("Written IPTC declares UTF-8, so non-ASCII text reads back intact")
	void iptcDeclaresUtf8(String image) throws Exception {
		List<IPTCDataSet> iptc = List.of(
				new IPTCDataSet(IPTCApplicationTag.OBJECT_NAME, "Grüße aus Köln"),
				new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "Ærø"),
				new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "東京"),
				new IPTCDataSet(IPTCApplicationTag.CAPTION_ABSTRACT, "Ça va"));

		byte[] out = transform(read(image), (is, os) -> Metadata.insertIPTC(is, os, iptc, false));

		IptcDirectory dir = MetadataExtractorOracle.read(out).getFirstDirectoryOfType(IptcDirectory.class);
		assertNotNull(dir, "IPTC missing");
		assertEquals("UTF-8", dir.getDescription(IptcDirectory.TAG_CODED_CHARACTER_SET));
		assertEquals("Grüße aus Köln", dir.getString(IptcDirectory.TAG_OBJECT_NAME));
		assertEquals(List.of("Ærø", "東京"), dir.getKeywords());
		assertEquals("Ça va", dir.getString(IptcDirectory.TAG_CAPTION));
		// Envelope record (1:90) precedes the application record in the byte stream
		IPTC read = (IPTC) Metadata.readMetadata(new ByteArrayInputStream(out)).get(MetadataType.IPTC);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		read.write(bytes);
		byte[] b = bytes.toByteArray();
		assertEquals(0x1c, b[0]);
		assertEquals(1, b[1], "first data set is in the envelope record");
		assertEquals(90, b[2]);
	}

	@Test
	@DisplayName("Merging into existing IPTC replaces single-value tags, appends repeatable ones, keeps one charset marker")
	void iptcMerge() throws Exception {
		byte[] withIptc = transform(read("images/1.jpg"), (is, os) -> Metadata.insertIPTC(is, os, List.of(
				new IPTCDataSet(IPTCApplicationTag.OBJECT_NAME, "Old title"),
				new IPTCDataSet(IPTCApplicationTag.BY_LINE, "Old author"),
				new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "old")), false));

		// List.of is immutable: the library must not try to add the old data sets to it
		byte[] out = transform(withIptc, (is, os) -> Metadata.insertIPTC(is, os, List.of(
				new IPTCDataSet(IPTCApplicationTag.OBJECT_NAME, "New title"),
				new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "new")), true));

		IptcDirectory dir = MetadataExtractorOracle.read(out).getFirstDirectoryOfType(IptcDirectory.class);
		assertEquals("New title", dir.getString(IptcDirectory.TAG_OBJECT_NAME));
		assertEquals("Old author", dir.getString(IptcDirectory.TAG_BY_LINE));
		assertEquals(List.of("old", "new"), dir.getKeywords());
		assertEquals(1, dir.getStringArray(IptcDirectory.TAG_OBJECT_NAME).length, "exactly one ObjectName");
		assertEquals(1, dir.getStringArray(IptcDirectory.TAG_CODED_CHARACTER_SET).length, "exactly one CodedCharacterSet");

		// Same through the one-pass path
		byte[] out2 = transform(withIptc, (is, os) -> Metadata.insertMetadata(is, os, new MetadataUpdate().iptc(List.of(
				new IPTCDataSet(IPTCApplicationTag.OBJECT_NAME, "New title"),
				new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "new")), true)));
		IptcDirectory dir2 = MetadataExtractorOracle.read(out2).getFirstDirectoryOfType(IptcDirectory.class);
		assertEquals("New title", dir2.getString(IptcDirectory.TAG_OBJECT_NAME));
		assertEquals(List.of("old", "new"), dir2.getKeywords());
	}

	@Test
	@DisplayName("Legacy Latin-1 data sets are re-encoded when merged under the UTF-8 declaration")
	void iptcLegacyLatin1() {
		byte[] latin1 = "Köln".getBytes(StandardCharsets.ISO_8859_1);
		IPTCDataSet legacy = new IPTCDataSet(IPTCRecord.APPLICATION, IPTCApplicationTag.CITY.getTag(), latin1);
		IPTCDataSet version = new IPTCDataSet(IPTCRecord.APPLICATION, IPTCApplicationTag.RECORD_VERSION.getTag(), new byte[] {0, 4});
		IPTCDataSet oldCharset = new IPTCDataSet(IPTCRecord.ENVELOP, IPTCEnvelopeTag.CODED_CHARACTER_SET.getTag(), new byte[] {0x1b, 0x2e, 0x41});
		IPTCDataSet fresh = new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "neu");

		List<IPTCDataSet> prepared = IPTC.prepareForWrite(Arrays.asList(fresh, legacy, version, oldCharset));

		assertEquals(4, prepared.size());
		assertEquals(IPTCRecord.ENVELOP.getRecordNumber(), prepared.get(0).getRecordNumber());
		assertArrayEquals(IPTC.UTF8_CODED_CHARACTER_SET, prepared.get(0).getData(), "the old charset marker is replaced");
		assertArrayEquals(new byte[] {0, 4}, byTag(prepared, IPTCApplicationTag.RECORD_VERSION).getData(), "binary record version untouched");
		assertArrayEquals("Köln".getBytes(StandardCharsets.UTF_8), byTag(prepared, IPTCApplicationTag.CITY).getData());
		assertEquals("Köln", byTag(prepared, IPTCApplicationTag.CITY).getDataAsString());
		// Valid UTF-8 (including pure ASCII) is left alone
		assertArrayEquals("neu".getBytes(StandardCharsets.UTF_8), byTag(prepared, IPTCApplicationTag.KEY_WORDS).getData());
	}

	// ----------------------------- helpers -----------------------------

	private static Exif artist() {
		Exif exif = new JpegExif();
		exif.addField(TiffTag.ARTIST, "Jane Tester");
		return exif;
	}

	private static IPTCDataSet byTag(List<IPTCDataSet> list, IPTCApplicationTag tag) {
		for (IPTCDataSet d : list)
			if (d.getRecordNumber() == IPTCRecord.APPLICATION.getRecordNumber() && d.getTag() == tag.getTag())
				return d;
		return null;
	}

	private static Directory makerNote(com.drew.metadata.Metadata me) {
		for (Directory d : me.getDirectories())
			if (d.getName().endsWith("Makernote"))
				return d;
		return null;
	}

	/** Every (directory, tag, description) triple except offsets that legitimately move. */
	private static List<String> describe(com.drew.metadata.Metadata me) {
		List<String> out = new ArrayList<>();
		for (Directory d : me.getDirectories())
			for (com.drew.metadata.Tag t : d.getTags())
				out.add(d.getName() + "/" + t.getTagName() + "=" + t.getDescription());
		return out;
	}

	/** Marker sequence up to SOS, with APP1 and APP2 qualified by their identifier. */
	private static List<String> markers(byte[] jpeg) {
		List<String> out = new ArrayList<>();
		int i = 2;
		while (i < jpeg.length) {
			int marker = jpeg[i + 1] & 0xff;
			if (marker == 0xDA) { out.add("SOS"); break; }
			int len = ((jpeg[i + 2] & 0xff) << 8) | (jpeg[i + 3] & 0xff);
			String name = String.format("%02X", marker);
			if (marker >= 0xE0 && marker <= 0xEF) name = "APP" + (marker - 0xE0);
			if (marker == 0xE1) {
				String id = new String(jpeg, i + 4, Math.min(30, len - 2), StandardCharsets.ISO_8859_1);
				name += id.startsWith("Exif") ? "-EXIF" : id.startsWith("http://ns.adobe.com/x") ? "-XMP" : "";
			}
			out.add(name);
			i += 2 + len;
		}
		return out;
	}

	/** From the SOS marker to the end of the file the bytes must be identical: the image data is never touched. */
	private static void assertSameScanData(byte[] expected, byte[] actual) {
		byte[] a = fromSos(expected);
		byte[] b = fromSos(actual);
		assertTrue(a.length > 2 && (a[1] & 0xff) == 0xDA, "scan data found");
		assertArrayEquals(a, b, "scan data changed");
	}

	private static byte[] fromSos(byte[] jpeg) {
		int i = 2;
		while (i + 3 < jpeg.length) {
			int marker = jpeg[i + 1] & 0xff;
			if (marker == 0xDA)
				return Arrays.copyOfRange(jpeg, i, jpeg.length);
			i += 2 + (((jpeg[i + 2] & 0xff) << 8) | (jpeg[i + 3] & 0xff));
		}
		return new byte[0];
	}

	/** Decoded pixels; only meaningful when the colour profile was not replaced. */
	private static void assertSamePixels(byte[] expected, byte[] actual) throws IOException {
		BufferedImage a = ImageIO.read(new ByteArrayInputStream(expected));
		BufferedImage b = ImageIO.read(new ByteArrayInputStream(actual));
		assertEquals(a.getWidth(), b.getWidth());
		assertEquals(a.getHeight(), b.getHeight());
		for (int y = 0; y < a.getHeight(); y += 7)
			for (int x = 0; x < a.getWidth(); x += 7)
				assertEquals(a.getRGB(x, y), b.getRGB(x, y), "pixel " + x + "," + y);
	}
}
