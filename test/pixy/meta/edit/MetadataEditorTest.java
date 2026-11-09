package pixy.meta.edit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.drew.metadata.Directory;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.icc.IccDirectory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.jpeg.JpegCommentDirectory;
import com.drew.metadata.photoshop.PhotoshopDirectory;
import com.drew.metadata.xmp.XmpDirectory;

import pixy.image.ImageType;
import pixy.meta.MetadataType;

/**
 * {@link MetadataEditor}: read, edit by name and value, apply without touching the image data.
 * Results are verified with metadata-extractor as an independent reader.
 */
public class MetadataEditorTest {

	private static final String XMP = "<x:xmpmeta xmlns:x='adobe:ns:meta/'>"
			+ "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
			+ "<rdf:Description rdf:about='' xmlns:xmp='http://ns.adobe.com/xap/1.0/' xmp:CreatorTool='pixymeta-editor'/>"
			+ "</rdf:RDF></x:xmpmeta>";

	private static final byte[] WEBP_LOSSY = Base64.getDecoder().decode(
			"UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");

	// ---- JPEG ----

	@Test
	@DisplayName("JPEG: edit EXIF, IPTC, XMP and ICC by name, keep everything else, image data untouched")
	void jpegEdit() throws Exception {
		byte[] original = read("images/sea.jpg"); // Nikon EXIF with maker note, IPTC, ICC, XMP
		com.drew.metadata.Metadata before = me(original);
		String model = before.getFirstDirectoryOfType(ExifIFD0Directory.class).getString(ExifIFD0Directory.TAG_MODEL);
		assertNotNull(model);

		MetadataEditor editor = MetadataEditor.read(original);
		assertEquals(ImageType.JPG, editor.getSourceType());
		assertTrue(editor.getSourceTypes().containsAll(Set.of(MetadataType.EXIF, MetadataType.IPTC, MetadataType.XMP, MetadataType.ICC_PROFILE)));
		assertFalse(editor.hasChanges());
		assertEquals(model, editor.getExifTag("Model"));
		assertEquals(model, editor.getExifValues().get("Model"));

		editor.setExifTag("Copyright", "(c) 2026 akitogo")
				.setExifTag("Date/Time Original", LocalDateTime.of(2026, 11, 9, 14, 30, 0))
				.setExifTag("ISO", 800)
				.setExifTag("F-Number", 4)
				.setExifTag("GPSLatitude", 52.503472)
				.setExifTag("GPSLongitude", -13.41)
				.setExifTag("GPSAltitude", -5.5)
				.removeExifTag("Software")
				.setIptcTag("Keywords", List.of("Köln", "Rhein"))
				.setIptcTag("Caption/Abstract", "Grüße vom Rhein")
				.setIptcTag("Copyright Notice", "(c) 2026 akitogo")
				.setXmp(XMP)
				.setIccProfile(null);
		assertTrue(editor.hasChanges());
		assertEquals("(c) 2026 akitogo", editor.getExifTag("Copyright"));
		assertEquals("2026:11:09 14:30:00", editor.getExifTag("DateTimeOriginal"));
		assertEquals("800", editor.getExifTag("ISOSpeedRatings"));
		assertEquals("F4", editor.getExifTag("FNumber"));
		assertEquals("S".equals(editor.getExifTag("GPSLatitudeRef")) ? "S" : "N", editor.getExifTag("GPSLatitudeRef"));
		assertEquals("W", editor.getExifTag("GPSLongitudeRef"));
		assertEquals(List.of("Köln", "Rhein"), editor.getIptcTag("Keywords"));
		assertEquals(List.of("Grüße vom Rhein"), editor.getIptcValues().get("CaptionAbstract"));
		assertNull(editor.getExifTag("Software"));
		assertNull(editor.getIccProfile());
		assertFalse(editor.getTypes().contains(MetadataType.ICC_PROFILE));

		byte[] out = editor.apply(original);

		com.drew.metadata.Metadata after = me(out);
		ExifIFD0Directory ifd0 = after.getFirstDirectoryOfType(ExifIFD0Directory.class);
		ExifSubIFDDirectory sub = after.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
		GpsDirectory gps = after.getFirstDirectoryOfType(GpsDirectory.class);
		IptcDirectory iptc = after.getFirstDirectoryOfType(IptcDirectory.class);
		assertEquals("(c) 2026 akitogo", ifd0.getString(ExifIFD0Directory.TAG_COPYRIGHT));
		assertEquals(model, ifd0.getString(ExifIFD0Directory.TAG_MODEL), "untouched tag kept");
		assertFalse(ifd0.containsTag(ExifIFD0Directory.TAG_SOFTWARE), "removed tag gone");
		assertEquals("2026:11:09 14:30:00", sub.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
		assertEquals(800, sub.getInt(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT));
		assertEquals(4.0, sub.getDouble(ExifSubIFDDirectory.TAG_FNUMBER), 1e-9);
		assertEquals(52.503472, gps.getGeoLocation().getLatitude(), 1e-6);
		assertEquals(-13.41, gps.getGeoLocation().getLongitude(), 1e-6);
		assertEquals(1, gps.getInt(GpsDirectory.TAG_ALTITUDE_REF));
		assertEquals(5.5, gps.getRational(GpsDirectory.TAG_ALTITUDE).doubleValue(), 1e-9);
		assertNotNull(makerNote(after), "maker note kept");
		assertEquals(describe(makerNote(before)), describe(makerNote(after)));
		assertEquals(List.of("Köln", "Rhein"), iptc.getKeywords());
		assertEquals("Grüße vom Rhein", iptc.getString(IptcDirectory.TAG_CAPTION));
		assertEquals("(c) 2026 akitogo", iptc.getString(IptcDirectory.TAG_COPYRIGHT_NOTICE));
		assertEquals("UTF-8", iptc.getDescription(IptcDirectory.TAG_CODED_CHARACTER_SET));
		IptcDirectory iptcBefore = before.getFirstDirectoryOfType(IptcDirectory.class);
		for (com.drew.metadata.Tag t : iptcBefore.getTags()) {
			int type = t.getTagType();
			if (type == IptcDirectory.TAG_KEYWORDS || type == IptcDirectory.TAG_CAPTION || type == IptcDirectory.TAG_COPYRIGHT_NOTICE
					|| type == IptcDirectory.TAG_CODED_CHARACTER_SET || type == IptcDirectory.TAG_APPLICATION_RECORD_VERSION) continue;
			assertEquals(iptcBefore.getString(type), iptc.getString(type), "IPTC " + t.getTagName() + " kept");
		}
		assertEquals("pixymeta-editor", after.getFirstDirectoryOfType(XmpDirectory.class).getXmpProperties().get("xmp:CreatorTool"));
		assertNull(after.getFirstDirectoryOfType(IccDirectory.class), "ICC removed");
		assertEquals(List.of(), errors(after));
		assertArrayEquals(scanData(original), scanData(out));
	}

	@Test
	@DisplayName("JPEG: an editor without changes copies the image byte for byte")
	void noChanges() throws Exception {
		byte[] original = read("images/12.jpg");
		MetadataEditor editor = MetadataEditor.read(original);
		assertArrayEquals(original, editor.apply(original));
	}

	@Test
	@DisplayName("JPEG: removeAll strips EXIF, IPTC, XMP, ICC and the Photoshop block")
	void removeAll() throws Exception {
		byte[] original = read("images/12.jpg");
		MetadataEditor editor = MetadataEditor.read(original);
		editor.removeAll();
		assertEquals(Set.of(), editor.getTypes());
		byte[] out = editor.apply(original);
		com.drew.metadata.Metadata after = me(out);
		assertNull(after.getFirstDirectoryOfType(ExifIFD0Directory.class));
		assertNull(after.getFirstDirectoryOfType(IptcDirectory.class));
		assertNull(after.getFirstDirectoryOfType(XmpDirectory.class));
		assertNull(after.getFirstDirectoryOfType(IccDirectory.class));
		assertNull(after.getFirstDirectoryOfType(PhotoshopDirectory.class));
		assertArrayEquals(scanData(original), scanData(out));
	}

	@Test
	@DisplayName("JPEG: an empty editor adds metadata to an image that has none")
	void addToPlainImage() throws Exception {
		byte[] original = read("images/1.jpg");
		MetadataEditor editor = MetadataEditor.create();
		editor.setExifTag("Artist", "Jane Tester").setIptcTag("Title", "Plain").setComments(List.of("hello"));
		byte[] out = editor.apply(original);
		com.drew.metadata.Metadata after = me(out);
		assertEquals("Jane Tester", after.getFirstDirectoryOfType(ExifIFD0Directory.class).getString(ExifIFD0Directory.TAG_ARTIST));
		assertEquals("Plain", after.getFirstDirectoryOfType(IptcDirectory.class).getString(IptcDirectory.TAG_OBJECT_NAME));
		assertEquals("hello", after.getFirstDirectoryOfType(JpegCommentDirectory.class).getString(JpegCommentDirectory.TAG_COMMENT));
		assertEquals(List.of(), errors(after));
	}

	@Test
	@DisplayName("Setting a tag to null removes it; setting Keywords replaces all keywords")
	void nullRemovesAndListReplaces() throws Exception {
		byte[] original = MetadataEditor.create().setIptcTag("Keywords", List.of("a", "b")).apply(read("images/sea.jpg"));
		MetadataEditor editor = MetadataEditor.read(original);
		assertEquals(List.of("a", "b"), editor.getIptcTag("Keywords"));
		editor.setIptcTag("Keywords", new String[] {"only"}).setExifTag("Model", null);
		byte[] out = editor.apply(original);
		com.drew.metadata.Metadata after = me(out);
		assertEquals(List.of("only"), after.getFirstDirectoryOfType(IptcDirectory.class).getKeywords());
		assertFalse(after.getFirstDirectoryOfType(ExifIFD0Directory.class).containsTag(ExifIFD0Directory.TAG_MODEL));
	}

	// ---- TIFF ----

	@Test
	@DisplayName("TIFF: descriptive IFD0 tags, EXIF SubIFD, IPTC and XMP are edited; structure untouched")
	void tiffEdit() throws Exception {
		byte[] original = read("images/iptc.tif"); // Canon EXIF, IPTC, XMP
		com.drew.metadata.Metadata before = me(original);
		MetadataEditor editor = MetadataEditor.read(original);
		assertEquals(ImageType.TIFF, editor.getSourceType());
		assertNull(editor.getExifValues().get("StripOffsets"), "structural tags are not metadata");
		String dateBefore = editor.getExifTag("DateTimeOriginal");
		assertNotNull(dateBefore);

		editor.setExifTag("Artist", "Jane Tester").setExifTag("Orientation", 1)
				.setIptcTag("Keywords", List.of("tiff"))
				.setXmp(null);
		byte[] out = editor.apply(original);

		com.drew.metadata.Metadata after = me(out);
		ExifIFD0Directory ifd0 = after.getFirstDirectoryOfType(ExifIFD0Directory.class);
		assertEquals("Jane Tester", ifd0.getString(ExifIFD0Directory.TAG_ARTIST));
		assertEquals(1, ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION));
		assertEquals(dateBefore, after.getFirstDirectoryOfType(ExifSubIFDDirectory.class).getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
		assertEquals(describe(makerNote(before)), describe(makerNote(after)));
		assertEquals(List.of("tiff"), after.getFirstDirectoryOfType(IptcDirectory.class).getKeywords());
		assertNull(after.getFirstDirectoryOfType(XmpDirectory.class));
		assertEquals(before.getFirstDirectoryOfType(ExifIFD0Directory.class).getInt(ExifIFD0Directory.TAG_IMAGE_WIDTH),
				ifd0.getInt(ExifIFD0Directory.TAG_IMAGE_WIDTH));
		assertEquals(List.of(), errors(after));
		javax.imageio.ImageIO.read(new ByteArrayInputStream(out)).getWidth(); // still decodable
	}

	// ---- PNG, GIF, WebP ----

	@Test
	@DisplayName("PNG: XMP and ICC are written, EXIF is refused with a clear error")
	void png() throws Exception {
		byte[] original = read("images/stonehouse.png");
		MetadataEditor editor = MetadataEditor.read(original);
		UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> editor.setExifTag("Artist", "x"));
		assertTrue(e.getMessage().contains("EXIF") && e.getMessage().contains("Png"), e.getMessage());
		assertThrows(UnsupportedOperationException.class, () -> editor.setIptcTag("Keywords", "x"));
		byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
		editor.setXmp(XMP).setIccProfile(icc);
		byte[] out = editor.apply(original);
		com.drew.metadata.Metadata after = me(out);
		assertEquals("pixymeta-editor", after.getFirstDirectoryOfType(XmpDirectory.class).getXmpProperties().get("xmp:CreatorTool"));
		assertEquals(icc.length, after.getFirstDirectoryOfType(IccDirectory.class).getInt(IccDirectory.TAG_PROFILE_BYTE_COUNT));
	}

	@Test
	@DisplayName("Applying EXIF to a PNG fails before anything is written")
	void applyUnsupportedTarget() throws Exception {
		MetadataEditor editor = MetadataEditor.create().setExifTag("Artist", "x");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertThrows(UnsupportedOperationException.class, () -> editor.apply(new ByteArrayInputStream(read("images/stonehouse.png")), out));
		assertEquals(0, out.size());
		// Removing a type the format cannot hold is simply ignored
		byte[] gif = read("images/butterfly.gif");
		assertArrayEquals(gif, MetadataEditor.create().remove(MetadataType.EXIF, MetadataType.IPTC).apply(gif));
	}

	@Test
	@DisplayName("WebP: EXIF and XMP from an empty editor")
	void webp() throws Exception {
		byte[] out = MetadataEditor.create().setExifTag("Artist", "Jane Tester").setXmp(XMP).apply(WEBP_LOSSY);
		com.drew.metadata.Metadata after = me(out);
		assertEquals("Jane Tester", after.getFirstDirectoryOfType(ExifIFD0Directory.class).getString(ExifIFD0Directory.TAG_ARTIST));
		assertEquals("pixymeta-editor", after.getFirstDirectoryOfType(XmpDirectory.class).getXmpProperties().get("xmp:CreatorTool"));
		MetadataEditor again = MetadataEditor.read(out);
		assertEquals(ImageType.WEBP, again.getSourceType());
		assertEquals("Jane Tester", again.getExifTag("Artist"));
		assertTrue(again.getXmp().contains("pixymeta-editor"));
	}

	// ---- files ----

	@Test
	@DisplayName("apply(Path) rewrites the file in place through a temporary file")
	void inPlace(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("photo.jpg");
		Files.write(file, read("images/1.jpg"));
		MetadataEditor editor = MetadataEditor.read(file);
		editor.setExifTag("Artist", "Jane Tester");
		editor.apply(file);
		assertEquals("Jane Tester", MetadataEditor.read(file).getExifTag("Artist"));
		assertEquals(List.of("photo.jpg"), Arrays.asList(dir.toFile().list()), "no temporary file left behind");

		Path copy = dir.resolve("copy.jpg");
		editor.setExifTag("Artist", "Second");
		editor.apply(file, copy);
		assertEquals("Jane Tester", MetadataEditor.read(file).getExifTag("Artist"));
		assertEquals("Second", MetadataEditor.read(copy).getExifTag("Artist"));
	}

	@Test
	@DisplayName("A failed apply leaves the original file untouched")
	void failedApplyKeepsOriginal(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("image.png");
		byte[] original = read("images/stonehouse.png");
		Files.write(file, original);
		MetadataEditor editor = MetadataEditor.create().setExifTag("Artist", "x");
		assertThrows(UnsupportedOperationException.class, () -> editor.apply(file));
		assertArrayEquals(original, Files.readAllBytes(file));
		assertEquals(List.of("image.png"), Arrays.asList(dir.toFile().list()));
	}

	// ---- validation ----

	@Test
	@DisplayName("Bad input is rejected with IllegalArgumentException")
	void validation() throws Exception {
		MetadataEditor editor = MetadataEditor.read(read("images/1.jpg"));
		assertThrows(IllegalArgumentException.class, () -> editor.setExifTag("NoSuchTag", 1));
		assertThrows(IllegalArgumentException.class, () -> editor.setExifTag("ImageWidth", 100));   // structural
		assertThrows(IllegalArgumentException.class, () -> editor.setExifTag("ExifSubIFD", 100));   // pointer
		assertThrows(IllegalArgumentException.class, () -> editor.setExifTag("MakerNote", "x"));
		assertThrows(IllegalArgumentException.class, () -> editor.setExifTag("FNumber", "fast"));
		assertThrows(IllegalArgumentException.class, () -> editor.setIptcTag("ObjectName", List.of("a", "b"))); // not repeatable
		assertThrows(IllegalArgumentException.class, () -> editor.setIptcTag("NoSuchTag", "x"));
		assertThrows(IllegalArgumentException.class, () -> editor.setXmp("<not xml"));
		assertFalse(editor.hasChanges(), "rejected calls leave the editor unchanged");
		assertThrows(IOException.class, () -> MetadataEditor.read(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}));
	}

	@Test
	@DisplayName("Decimal degrees become degrees, minutes and seconds to 1/10000")
	void degreesMinutesSeconds() {
		assertArrayEquals(new int[] {52, 1, 30, 1, 124992, 10000}, MetadataEditor.toDegreesMinutesSeconds(52.503472));
		assertArrayEquals(new int[] {13, 1, 0, 1, 0, 10000}, MetadataEditor.toDegreesMinutesSeconds(13));
		assertArrayEquals(new int[] {1, 1, 0, 1, 0, 10000}, MetadataEditor.toDegreesMinutesSeconds(0.99999999999));
		assertThrows(IllegalArgumentException.class, () -> MetadataEditor.toDegreesMinutesSeconds(181));
	}

	@Test
	@DisplayName("Capability matrix")
	void capabilities() {
		assertEquals(Set.of(MetadataType.EXIF, MetadataType.XMP, MetadataType.ICC_PROFILE), MetadataEditor.writableTypes(ImageType.WEBP));
		assertFalse(MetadataEditor.writableTypes(ImageType.PNG).contains(MetadataType.EXIF));
		assertTrue(MetadataEditor.removableTypes(ImageType.PNG).contains(MetadataType.EXIF));
		assertTrue(MetadataEditor.writableTypes(ImageType.BMP).isEmpty());
		assertFalse(MetadataEditor.isSupported(ImageType.BMP));
	}

	// ----------------------------- helpers -----------------------------

	static byte[] read(String path) throws IOException {
		return Files.readAllBytes(new File(path).toPath());
	}

	private static com.drew.metadata.Metadata me(byte[] image) throws IOException {
		try {
			return com.drew.imaging.ImageMetadataReader.readMetadata(new ByteArrayInputStream(image));
		} catch (com.drew.imaging.ImageProcessingException e) {
			throw new IOException(e);
		}
	}

	private static List<String> errors(com.drew.metadata.Metadata me) {
		List<String> out = new java.util.ArrayList<>();
		for (Directory d : me.getDirectories())
			for (String e : d.getErrors()) out.add(d.getName() + ": " + e);
		return out;
	}

	private static Directory makerNote(com.drew.metadata.Metadata me) {
		for (Directory d : me.getDirectories())
			if (d.getName().endsWith("Makernote")) return d;
		return null;
	}

	private static List<String> describe(Directory d) {
		List<String> out = new java.util.ArrayList<>();
		if (d == null) return out;
		for (com.drew.metadata.Tag t : d.getTags()) out.add(t.getTagName() + "=" + t.getDescription());
		return out;
	}

	private static byte[] scanData(byte[] jpeg) {
		int i = 2;
		while (i + 3 < jpeg.length) {
			int marker = jpeg[i + 1] & 0xff;
			if (marker == 0xDA) return Arrays.copyOfRange(jpeg, i, jpeg.length);
			i += 2 + (((jpeg[i + 2] & 0xff) << 8) | (jpeg[i + 3] & 0xff));
		}
		return new byte[0];
	}

	@SuppressWarnings("unused")
	private static Map<String, String> unused() { return Map.of(); }
}
