package pixy.meta;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pixy.meta.MetadataExtractorOracle.errors;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.drew.metadata.Directory;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.gif.GifCommentDirectory;
import com.drew.metadata.gif.GifImageDirectory;
import com.drew.metadata.icc.IccDirectory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.jpeg.JpegCommentDirectory;
import com.drew.metadata.xmp.XmpDirectory;

import pixy.image.tiff.FieldType;
import pixy.image.tiff.TiffTag;
import pixy.meta.exif.Exif;
import pixy.meta.exif.ExifTag;
import pixy.meta.iptc.IPTCApplicationTag;
import pixy.meta.iptc.IPTCDataSet;
import pixy.meta.jpeg.JpegExif;
import pixy.meta.tiff.TiffExif;

/**
 * Cross-validates PIXYMETA against metadata-extractor, an independent parser: metadata written
 * by PIXYMETA must read back with the same values there, and metadata read by both must agree.
 * Catches output that PIXYMETA itself can read but other software cannot.
 */
public class CrossValidationTest {

	private static final String XMP = "<x:xmpmeta xmlns:x='adobe:ns:meta/'>"
			+ "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
			+ "<rdf:Description rdf:about='' xmlns:xmp='http://ns.adobe.com/xap/1.0/' xmp:CreatorTool='pixymeta-test'/>"
			+ "</rdf:RDF></x:xmpmeta>";

	// ---- EXIF ----

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"images/1.jpg", "images/12.jpg", "images/f1.tif", "images/exif.tif"})
	@DisplayName("Inserted EXIF reads back in metadata-extractor")
	void exifRoundTrip(String image) throws Exception {
		boolean tiff = image.endsWith(".tif");
		Exif exif = tiff ? new TiffExif() : new JpegExif();
		exif.addImageField(TiffTag.ARTIST, FieldType.ASCII, "Jane Tester");
		exif.addImageField(TiffTag.COPYRIGHT, FieldType.ASCII, "(c) pixymeta");
		exif.addExifField(ExifTag.DATE_TIME_ORIGINAL, FieldType.ASCII, "2026:10:26 12:34:56");
		exif.addExifField(ExifTag.ISO_SPEED_RATINGS, FieldType.SHORT, new short[] {400});
		exif.addExifField(ExifTag.FNUMBER, FieldType.RATIONAL, new int[] {28, 10});

		byte[] out = transform(read(image), (is, os) -> Metadata.insertExif(is, os, exif, true));

		com.drew.metadata.Metadata me = MetadataExtractorOracle.read(out);
		ExifIFD0Directory ifd0 = me.getFirstDirectoryOfType(ExifIFD0Directory.class);
		ExifSubIFDDirectory sub = me.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
		assertNotNull(ifd0, "IFD0 missing");
		assertNotNull(sub, "EXIF SubIFD missing");
		assertEquals("Jane Tester", ifd0.getString(ExifIFD0Directory.TAG_ARTIST));
		assertEquals("(c) pixymeta", ifd0.getString(ExifIFD0Directory.TAG_COPYRIGHT));
		assertEquals("2026:10:26 12:34:56", sub.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
		assertEquals(400, sub.getInt(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT));
		assertEquals(2.8, sub.getDouble(ExifSubIFDDirectory.TAG_FNUMBER), 1e-9);
		assertNoExifErrors(me);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"images/example.jpg", "images/sea.jpg", "images/iptc.tif"})
	@DisplayName("Updating EXIF keeps the camera maker note readable (Canon, Nikon)")
	void exifUpdatePreservesMakerNote(String image) throws IOException {
		byte[] original = read(image);
		Exif exif = image.endsWith(".tif") ? new TiffExif() : new JpegExif();
		exif.addImageField(TiffTag.ARTIST, FieldType.ASCII, "Jane Tester");

		byte[] out = transform(original, (is, os) -> Metadata.insertExif(is, os, exif, true));

		// The maker note moves when EXIF is rewritten. It must still decode to exactly what the
		// original decodes to, in metadata-extractor and in PIXYMETA itself.
		com.drew.metadata.Metadata before = MetadataExtractorOracle.read(original);
		com.drew.metadata.Metadata after = MetadataExtractorOracle.read(out);
		Directory makerNoteBefore = makerNoteDirectory(before);
		Directory makerNoteAfter = makerNoteDirectory(after);
		assertNotNull(makerNoteBefore);
		assertNotNull(makerNoteAfter, "maker note lost: " + errors(after));
		assertEquals(describe(makerNoteBefore), describe(makerNoteAfter));
		assertNoExifErrors(after);

		Exif rewritten = (Exif) Metadata.readMetadata(new ByteArrayInputStream(out)).get(MetadataType.EXIF);
		assertEquals(List.of(), MetadataExtractorOracle.compareMakerNote(rewritten.getMakerNote(), after));
	}

	// ---- IPTC ----

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"images/wizard.jpg", "images/1.jpg", "images/iptc-envelope.tif"})
	@DisplayName("Inserted IPTC reads back in metadata-extractor")
	void iptcRoundTrip(String image) throws IOException {
		List<IPTCDataSet> iptc = Arrays.asList(
				new IPTCDataSet(IPTCApplicationTag.OBJECT_NAME, "Test title"),
				new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "alpha"),
				new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "beta"),
				new IPTCDataSet(IPTCApplicationTag.BY_LINE, "Jane Tester"),
				new IPTCDataSet(IPTCApplicationTag.CAPTION_ABSTRACT, "A caption"));

		byte[] out = transform(read(image), (is, os) -> Metadata.insertIPTC(is, os, iptc, false));

		IptcDirectory dir = MetadataExtractorOracle.read(out).getFirstDirectoryOfType(IptcDirectory.class);
		assertNotNull(dir, "IPTC missing");
		assertEquals("Test title", dir.getString(IptcDirectory.TAG_OBJECT_NAME));
		assertEquals(List.of("alpha", "beta"), dir.getKeywords());
		assertEquals("Jane Tester", dir.getString(IptcDirectory.TAG_BY_LINE));
		assertEquals("A caption", dir.getString(IptcDirectory.TAG_CAPTION));
	}

	// ---- XMP ----

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"images/1.jpg", "images/f1.tif", "images/butterfly.png", "images/butterfly.gif"})
	@DisplayName("Inserted XMP reads back in metadata-extractor")
	void xmpRoundTrip(String image) throws IOException {
		byte[] out = transform(read(image), (is, os) -> Metadata.insertXMP(is, os, XMP));

		XmpDirectory dir = MetadataExtractorOracle.read(out).getFirstDirectoryOfType(XmpDirectory.class);
		assertNotNull(dir, "XMP missing");
		assertEquals("pixymeta-test", dir.getXmpProperties().get("xmp:CreatorTool"));
	}

	@Test
	@DisplayName("PNG: inserting XMP keeps the other iTXt chunks")
	void pngXmpKeepsOtherTextChunks() throws IOException {
		// First give the image a non-XMP iTXt chunk, then insert XMP
		byte[] withText = transform(read("images/stonehouse.png"), (is, os) ->
			pixy.meta.png.PNGMeta.insertTextChunk(pixy.image.png.ChunkType.ITXT, "Title", "Stone house", is, os));
		byte[] out = transform(withText, (is, os) -> Metadata.insertXMP(is, os, XMP));

		com.drew.metadata.Metadata me = MetadataExtractorOracle.read(out);
		assertNotNull(me.getFirstDirectoryOfType(XmpDirectory.class), "XMP missing");
boolean titleKept = false;
		for (Directory d : me.getDirectories()) {
			for (com.drew.metadata.Tag t : d.getTags())
				titleKept |= String.valueOf(t.getDescription()).contains("Stone house");
		}
		assertTrue(titleKept, "the existing iTXt chunk was dropped");
	}

	// ---- ICC profile ----

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"images/1.jpg", "images/f1.tif", "images/stonehouse.png"})
	@DisplayName("Inserted ICC profile reads back in metadata-extractor")
	void iccRoundTrip(String image) throws Exception {
		byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();

		byte[] out = transform(read(image), (is, os) -> Metadata.insertICCProfile(is, os, icc));

		IccDirectory dir = MetadataExtractorOracle.read(out).getFirstDirectoryOfType(IccDirectory.class);
		assertNotNull(dir, "ICC profile missing");
		assertEquals(icc.length, dir.getInt(IccDirectory.TAG_PROFILE_BYTE_COUNT));
		assertTrue(dir.getDescription(IccDirectory.TAG_TAG_desc).contains("sRGB"));
	}

	// ---- Comments ----

	@Test
	@DisplayName("JPEG: inserted comment reads back in metadata-extractor")
	void jpegCommentRoundTrip() throws IOException {
		byte[] out = transform(read("images/1.jpg"), (is, os) -> Metadata.insertComment(is, os, "Hello from pixymeta"));

		JpegCommentDirectory dir = MetadataExtractorOracle.read(out).getFirstDirectoryOfType(JpegCommentDirectory.class);
		assertNotNull(dir, "comment missing");
		assertEquals("Hello from pixymeta", dir.getString(JpegCommentDirectory.TAG_COMMENT));
	}

	// ---- Removal ----

	@Test
	@DisplayName("JPEG: removed metadata is gone for metadata-extractor too")
	void jpegRemoveMetadata() throws IOException {
		byte[] original = read("images/12.jpg");
		com.drew.metadata.Metadata before = MetadataExtractorOracle.read(original);
		assertNotNull(before.getFirstDirectoryOfType(ExifIFD0Directory.class));
		assertNotNull(before.getFirstDirectoryOfType(XmpDirectory.class));
		assertNotNull(before.getFirstDirectoryOfType(IptcDirectory.class));
		assertNotNull(before.getFirstDirectoryOfType(IccDirectory.class));

		byte[] out = transform(original, (is, os) -> Metadata.removeMetadata(is, os,
				MetadataType.EXIF, MetadataType.XMP, MetadataType.IPTC, MetadataType.ICC_PROFILE));

		com.drew.metadata.Metadata after = MetadataExtractorOracle.read(out);
		assertNull(after.getFirstDirectoryOfType(ExifIFD0Directory.class));
		assertNull(after.getFirstDirectoryOfType(XmpDirectory.class));
		assertNull(after.getFirstDirectoryOfType(IptcDirectory.class));
		assertNull(after.getFirstDirectoryOfType(IccDirectory.class));
	}

	@Test
	@DisplayName("PNG: removing XMP, ICC and comments keeps other text chunks and the pixels")
	void pngRemoveMetadata() throws IOException {
		byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
		byte[] original = transform(read("images/stonehouse.png"), (is, os) ->
			pixy.meta.png.PNGMeta.insertTextChunk(pixy.image.png.ChunkType.ITXT, "Title", "Stone house", is, os));
		original = transform(original, (is, os) -> Metadata.insertXMP(is, os, XMP));
		original = transform(original, (is, os) -> Metadata.insertICCProfile(is, os, icc));
		original = transform(original, (is, os) -> Metadata.insertComments(is, os, List.of("A comment")));
		com.drew.metadata.Metadata before = MetadataExtractorOracle.read(original);
		assertNotNull(before.getFirstDirectoryOfType(XmpDirectory.class));
		assertNotNull(before.getFirstDirectoryOfType(IccDirectory.class));
		assertTrue(describeAll(before).contains("A comment"));

		ByteArrayOutputStream os = new ByteArrayOutputStream();
		Map<MetadataType, Metadata> removed = Metadata.removeMetadata(new ByteArrayInputStream(original), os,
				MetadataType.XMP, MetadataType.ICC_PROFILE, MetadataType.COMMENT);
		byte[] out = os.toByteArray();

		com.drew.metadata.Metadata after = MetadataExtractorOracle.read(out);
		assertNull(after.getFirstDirectoryOfType(XmpDirectory.class));
		assertNull(after.getFirstDirectoryOfType(IccDirectory.class));
		assertFalse(describeAll(after).contains("A comment"));
		assertTrue(describeAll(after).contains("Stone house"), "unrelated text chunk removed");
		assertTrue(removed.containsKey(MetadataType.XMP) && removed.containsKey(MetadataType.ICC_PROFILE));
		assertSamePixels(original, out);

		// PNG_TEXTUAL removes every text chunk, the title too
		byte[] noText = transform(original, (is, o) -> Metadata.removeMetadata(is, o, MetadataType.PNG_TEXTUAL));
		assertFalse(describeAll(MetadataExtractorOracle.read(noText)).contains("Stone house"));
		assertNull(MetadataExtractorOracle.read(noText).getFirstDirectoryOfType(XmpDirectory.class));
		assertSamePixels(original, noText);
	}

	@Test
	@DisplayName("PNG: corrupt metadata can still be removed")
	void pngRemoveCorruptIccProfile() throws IOException {
		// An iCCP chunk whose zlib stream is cut short: readMetadata rejects it
		byte[] original = read("images/ProPhoto.png");
		List<pixy.image.png.Chunk> chunks = pixy.meta.png.PNGMeta.readChunks(new ByteArrayInputStream(original));
		for (int i = 0; i < chunks.size(); i++) {
			pixy.image.png.Chunk chunk = chunks.get(i);
			if (chunk.getChunkType() == pixy.image.png.ChunkType.ICCP) {
				byte[] data = Arrays.copyOf(chunk.getData(), chunk.getData().length / 2);
				chunks.set(i, new pixy.image.png.Chunk(pixy.image.png.ChunkType.ICCP, data.length, data, 0));
			}
		}
		ByteArrayOutputStream corrupt = new ByteArrayOutputStream();
		corrupt.write(Arrays.copyOf(original, 8));
		pixy.meta.png.PNGMeta.serializeChunks(chunks, corrupt);

		byte[] out = transform(corrupt.toByteArray(), (is, os) -> Metadata.removeMetadata(is, os, MetadataType.ICC_PROFILE));

		assertNull(MetadataExtractorOracle.read(out).getFirstDirectoryOfType(IccDirectory.class));
		assertSamePixels(original, out);
	}

	@Test
	@DisplayName("GIF: removing XMP and comments keeps every frame")
	void gifRemoveMetadata() throws IOException {
		// butterfly.gif is animated and has a comment; add XMP
		byte[] original = transform(read("images/butterfly.gif"), (is, os) -> Metadata.insertXMP(is, os, XMP));
		com.drew.metadata.Metadata before = MetadataExtractorOracle.read(original);
		assertNotNull(before.getFirstDirectoryOfType(XmpDirectory.class));
		assertNotNull(before.getFirstDirectoryOfType(GifCommentDirectory.class));

		ByteArrayOutputStream os = new ByteArrayOutputStream();
		Map<MetadataType, Metadata> removed = Metadata.removeMetadata(new ByteArrayInputStream(original), os, MetadataType.XMP);
		byte[] onlyXmpRemoved = os.toByteArray();
		byte[] out = transform(original, (is, o) -> Metadata.removeMetadata(is, o, MetadataType.XMP, MetadataType.COMMENT));

		com.drew.metadata.Metadata xmpRemoved = MetadataExtractorOracle.read(onlyXmpRemoved);
		assertNull(xmpRemoved.getFirstDirectoryOfType(XmpDirectory.class));
		assertNotNull(xmpRemoved.getFirstDirectoryOfType(GifCommentDirectory.class), "comment removed although not asked");
		assertTrue(removed.containsKey(MetadataType.XMP));
		assertEquals(((pixy.meta.xmp.XMP) Metadata.readMetadata(new ByteArrayInputStream(original)).get(MetadataType.XMP)).getData().length,
				((pixy.meta.xmp.XMP) removed.get(MetadataType.XMP)).getData().length);

		com.drew.metadata.Metadata after = MetadataExtractorOracle.read(out);
		assertNull(after.getFirstDirectoryOfType(XmpDirectory.class));
		assertNull(after.getFirstDirectoryOfType(GifCommentDirectory.class));
		assertEquals(before.getDirectoriesOfType(GifImageDirectory.class).size(), after.getDirectoriesOfType(GifImageDirectory.class).size());
		assertEquals(List.of(), errors(after));
		assertSameFrames(original, out);
	}

	@Test
	@DisplayName("GIF: a stream truncated inside a block is rejected with IOException")
	void gifRemoveTruncated() throws IOException {
		byte[] gif = read("images/butterfly.gif");
		byte[] truncated = Arrays.copyOf(gif, gif.length / 2);
		assertThrows(IOException.class, () -> transform(truncated, (is, os) -> Metadata.removeMetadata(is, os, MetadataType.COMMENT)));
	}

	// ---- Unsupported operations ----

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"insertIPTC images/butterfly.png", "insertIPTC images/butterfly.gif", "insertIRB images/stonehouse.png",
			"insertComments images/niagra_falls.bmp", "insertExif images/butterfly.gif", "insertXMP images/niagra_falls.bmp",
			"insertICCProfile images/butterfly.gif", "removeMetadata images/niagra_falls.bmp",
			"removeMetadata images/butterfly.gif"})
	@DisplayName("An operation the format does not support copies the image through unchanged")
	void unsupportedOperationCopiesInput(String operationAndImage) throws IOException {
		String operation = operationAndImage.split(" ")[0];
		byte[] original = read(operationAndImage.split(" ")[1]);

		byte[] out = transform(original, (is, os) -> {
			switch (operation) {
				case "insertIPTC": Metadata.insertIPTC(is, os, List.of(new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "x")), false); break;
				case "insertIRB": Metadata.insertIRB(is, os, List.of(), false); break;
				case "insertComments": Metadata.insertComments(is, os, List.of("x")); break;
				case "insertExif": Metadata.insertExif(is, os, new JpegExif(), false); break;
				case "insertXMP": Metadata.insertXMP(is, os, XMP); break;
				case "insertICCProfile": Metadata.insertICCProfile(is, os, ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData()); break;
				case "removeMetadata": Metadata.removeMetadata(is, os, MetadataType.XMP); break;
				default: throw new IllegalArgumentException(operation);
			}
		});

		assertArrayEquals(original, out);
	}

	// ---- Reading ----

	static Stream<String> sampleImages() {
		File[] files = new File("images").listFiles(File::isFile);
		return Arrays.stream(files).map(File::getPath).sorted();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("sampleImages")
	@DisplayName("PIXYMETA and metadata-extractor read the same EXIF and maker notes")
	void readAgreement(String image) throws IOException {
		byte[] bytes = read(image);
		Map<MetadataType, Metadata> pixy = Metadata.readMetadata(new ByteArrayInputStream(bytes));
		com.drew.metadata.Metadata me = MetadataExtractorOracle.read(bytes);
		assertEquals(List.of(), MetadataExtractorOracle.compareRead(pixy, me));
	}

	@Test
	@DisplayName("Maker notes of the sample images are decoded")
	void makerNotesDecoded() throws IOException {
		assertEquals("Canon Makernote", makerNoteName("images/example.jpg"));
		assertEquals("Canon Makernote", makerNoteName("images/iptc.tif"));
		assertEquals("Nikon Makernote", makerNoteName("images/sea.jpg"));
		assertNull(makerNoteName("images/12.jpg"));
	}

	// ----------------------------- helpers -----------------------------

	interface Transformation {
		void apply(ByteArrayInputStream is, ByteArrayOutputStream os) throws IOException;
	}

	static byte[] transform(byte[] input, Transformation transformation) throws IOException {
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		transformation.apply(new ByteArrayInputStream(input), os);
		return os.toByteArray();
	}

	static byte[] read(String path) throws IOException {
		return Files.readAllBytes(new File(path).toPath());
	}

	private static String makerNoteName(String image) throws IOException {
		Exif exif = (Exif) Metadata.readMetadata(image).get(MetadataType.EXIF);
		return (exif == null || exif.getMakerNote() == null) ? null : exif.getMakerNote().getName();
	}

	private static Directory makerNoteDirectory(com.drew.metadata.Metadata me) {
		for (Directory d : me.getDirectories()) {
			if (d.getName().endsWith("Makernote"))
				return d;
		}
		return null;
	}

	private static List<String> describe(Directory directory) {
		List<String> tags = new ArrayList<>();
		for (com.drew.metadata.Tag tag : directory.getTags())
			tags.add(tag.getTagName() + "=" + tag.getDescription());
		return tags;
	}

	private static String describeAll(com.drew.metadata.Metadata me) {
		StringBuilder sb = new StringBuilder();
		for (Directory d : me.getDirectories())
			for (com.drew.metadata.Tag t : d.getTags())
				sb.append(t.getDescription()).append('\n');
		return sb.toString();
	}

	private static void assertSamePixels(byte[] expected, byte[] actual) throws IOException {
		BufferedImage a = ImageIO.read(new ByteArrayInputStream(expected));
		BufferedImage b = ImageIO.read(new ByteArrayInputStream(actual));
		assertEquals(a.getWidth(), b.getWidth());
		assertEquals(a.getHeight(), b.getHeight());
		assertArrayEquals(a.getRGB(0, 0, a.getWidth(), a.getHeight(), null, 0, a.getWidth()),
				b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth()));
	}

	/** Decodes every frame of both GIFs and compares them pixel by pixel. */
	private static void assertSameFrames(byte[] expected, byte[] actual) throws IOException {
		List<BufferedImage> a = frames(expected), b = frames(actual);
		assertEquals(a.size(), b.size());
		for (int i = 0; i < a.size(); i++) {
			BufferedImage x = a.get(i), y = b.get(i);
			assertArrayEquals(x.getRGB(0, 0, x.getWidth(), x.getHeight(), null, 0, x.getWidth()),
					y.getRGB(0, 0, y.getWidth(), y.getHeight(), null, 0, y.getWidth()), "frame " + i);
		}
	}

	private static List<BufferedImage> frames(byte[] gif) throws IOException {
		ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(gif))) {
			reader.setInput(iis);
			List<BufferedImage> frames = new ArrayList<>();
			for (int i = 0, n = reader.getNumImages(true); i < n; i++)
				frames.add(reader.read(i));
			return frames;
		} finally {
			reader.dispose();
		}
	}

	private static void assertNoExifErrors(com.drew.metadata.Metadata me) {
		List<String> exifErrors = new ArrayList<>();
		for (String error : errors(me)) {
			if (error.startsWith("Exif") || error.contains("Makernote"))
				exifErrors.add(error);
		}
		assertEquals(List.of(), exifErrors, "metadata-extractor reported errors in the EXIF written");
	}
}
