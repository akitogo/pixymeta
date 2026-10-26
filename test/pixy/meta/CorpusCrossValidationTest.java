package pixy.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static pixy.meta.CrossValidationTest.transform;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.drew.metadata.Directory;
import com.drew.metadata.Tag;
import com.drew.metadata.webp.WebpDirectory;
import com.drew.metadata.xmp.XmpDirectory;

import pixy.image.tiff.FieldType;
import pixy.image.tiff.TiffTag;
import pixy.meta.exif.Exif;
import pixy.meta.jpeg.JpegExif;

/**
 * Runs the metadata-extractor cross-checks over a directory of real-world images, for example a
 * checkout of https://github.com/drewnoakes/metadata-extractor-images. Skipped unless the
 * directory is given:
 *
 * <pre>mvn test -Dtest=CorpusCrossValidationTest -Dpixymeta.corpus=/path/to/images</pre>
 *
 * For every JPEG, TIFF, PNG, GIF and WebP file below it checks that reading never fails with a
 * runtime exception, that both libraries read the same EXIF and maker notes, that updating EXIF
 * keeps the maker note intact, and that XMP inserted into a WebP file reads back.
 */
public class CorpusCrossValidationTest {

	private static final List<String> EXTENSIONS = List.of(".jpg", ".jpeg", ".tif", ".tiff", ".png", ".gif", ".webp");

	/**
	 * Known differences in metadata-extractor-images, by file name. In both cases the maker note
	 * points at values outside its own bytes: metadata-extractor reads them from the surrounding
	 * EXIF data, PIXYMETA only decodes (and can only carry over on rewrite) the maker note itself.
	 */
	private static final Map<String, String> KNOWN_DIFFERENCES = Map.of(
			"Kyocera FC-S3.jpg", "Proprietary Thumbnail Format Data",
			"Sanyo S1.JPG", "EXIF update changed Sanyo Makernote",
			"Sanyo SR6.jpg", "EXIF update changed Sanyo Makernote");

	@TestFactory
	Stream<DynamicTest> corpus() throws IOException {
		String corpus = System.getProperty("pixymeta.corpus");
		Assumptions.assumeTrue(corpus != null && !corpus.isEmpty(), "set -Dpixymeta.corpus=<dir> to run");
		Path root = Paths.get(corpus);
		List<Path> files = new ArrayList<>();
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				// Skip .git and other hidden directories
				return (!dir.equals(root) && dir.getFileName().toString().startsWith(".")) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (attrs.isRegularFile() && isImage(file))
					files.add(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException e) {
				return FileVisitResult.CONTINUE;
			}
		});
		Collections.sort(files);
		return files.stream().map(file -> DynamicTest.dynamicTest(root.relativize(file).toString(), () -> check(file)));
	}

	private static boolean isImage(Path file) {
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		return EXTENSIONS.stream().anyMatch(name::endsWith);
	}

	private static void check(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		com.drew.metadata.Metadata me;
		try {
			me = MetadataExtractorOracle.read(bytes);
		} catch (IOException | RuntimeException e) {
			// No reference to compare against (metadata-extractor 2.19.0 itself crashes on a few files)
			Assumptions.abort("metadata-extractor cannot read " + file + ": " + e);
			return;
		}

		Map<MetadataType, Metadata> pixy;
		try {
			pixy = Metadata.readMetadata(new ByteArrayInputStream(bytes));
		} catch (IOException e) {
			fail("PIXYMETA failed on " + file + ", which metadata-extractor reads: " + e);
			return;
		} catch (IllegalArgumentException e) {
			Assumptions.abort("format not supported by PIXYMETA: " + e.getMessage());
			return;
		}
		// Forces lazy parsing of every block so a RuntimeException there surfaces too
		for (Metadata metadata : pixy.values())
			metadata.iterator().forEachRemaining(entry -> {});

		List<String> diffs = new ArrayList<>(MetadataExtractorOracle.compareRead(pixy, me));
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		if ((name.endsWith(".jpg") || name.endsWith(".jpeg")) && pixy.containsKey(MetadataType.EXIF))
			diffs.addAll(checkExifUpdate(bytes, me));
		if (name.endsWith(".webp"))
			diffs.addAll(checkWebPInsert(bytes, me));
		String known = KNOWN_DIFFERENCES.get(file.getFileName().toString());
		if (known != null)
			diffs.removeIf(diff -> diff.contains(known));
		assertEquals(List.of(), diffs, file.toString());
	}

	/** Rewrites the EXIF and verifies the maker note still decodes identically. */
	private static List<String> checkExifUpdate(byte[] bytes, com.drew.metadata.Metadata before) throws IOException {
		Exif exif = new JpegExif();
		exif.addImageField(TiffTag.ARTIST, FieldType.ASCII, "pixymeta corpus test");
		byte[] out = transform(bytes, (is, os) -> Metadata.insertExif(is, os, exif, true));
		com.drew.metadata.Metadata after = MetadataExtractorOracle.read(out);
		List<String> diffs = new ArrayList<>();
		for (Directory directory : before.getDirectories()) {
			if (!directory.getName().endsWith("Makernote"))
				continue;
			Directory rewritten = MetadataExtractorOracle.directory(after, directory.getName());
			if (rewritten == null) {
				diffs.add("EXIF update lost the " + directory.getName());
				continue;
			}
			for (Tag tag : directory.getTags()) {
				String expected = tag.getDescription();
				String actual = rewritten.getDescription(tag.getTagType());
				if (expected != null && !expected.equals(actual))
					diffs.add("EXIF update changed " + directory.getName() + " " + tag.getTagName() + ": '" + expected + "' -> '" + actual + "'");
			}
		}
		return diffs;
	}

	/** Inserts XMP and verifies metadata-extractor reads it and the image size is unchanged. */
	private static List<String> checkWebPInsert(byte[] bytes, com.drew.metadata.Metadata before) throws IOException {
		String xmp = "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
				+ "<rdf:Description rdf:about='' xmlns:xmp='http://ns.adobe.com/xap/1.0/' xmp:CreatorTool='pixymeta-corpus'/>"
				+ "</rdf:RDF></x:xmpmeta>";
		byte[] out = transform(bytes, (is, os) -> Metadata.insertXMP(is, os, xmp));
		com.drew.metadata.Metadata after = MetadataExtractorOracle.read(out);
		List<String> diffs = new ArrayList<>();
		XmpDirectory dir = after.getFirstDirectoryOfType(XmpDirectory.class);
		if (dir == null || !"pixymeta-corpus".equals(dir.getXmpProperties().get("xmp:CreatorTool")))
			diffs.add("inserted XMP not readable");
		WebpDirectory sizeBefore = before.getFirstDirectoryOfType(WebpDirectory.class);
		WebpDirectory sizeAfter = after.getFirstDirectoryOfType(WebpDirectory.class);
		assertNotNull(sizeAfter);
		if (sizeBefore != null && !String.valueOf(sizeBefore.getInteger(WebpDirectory.TAG_IMAGE_WIDTH)).equals(String.valueOf(sizeAfter.getInteger(WebpDirectory.TAG_IMAGE_WIDTH))))
			diffs.add("image width changed");
		return diffs;
	}
}
