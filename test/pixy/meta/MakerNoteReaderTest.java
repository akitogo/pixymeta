package pixy.meta;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import pixy.image.tiff.MakerNoteField;
import pixy.meta.exif.Exif;
import pixy.meta.exif.ExifTag;
import pixy.meta.exif.makernote.MakerNote;
import pixy.meta.exif.makernote.MakerNoteDirectory;
import pixy.meta.exif.makernote.MakerNoteEntry;
import pixy.meta.exif.makernote.MakerNoteIFD;
import pixy.meta.exif.makernote.MakerNoteReader;

/**
 * Maker note decoding details and robustness against malformed input. Agreement with
 * metadata-extractor on real files is covered by {@link CrossValidationTest}.
 */
public class MakerNoteReaderTest {

	@Test
	@DisplayName("Canon: camera settings array is split into named tags, values readable")
	void canonDecoding() throws IOException {
		MakerNote makerNote = makerNote("images/example.jpg");
		assertNotNull(makerNote);
		MakerNoteIFD canon = makerNote.getMainDirectory();
		assertEquals(MakerNoteDirectory.CANON, canon.getDirectory());
		assertEquals("IMG:PowerShot SX20 IS JPEG", canon.getEntry(0x0006).getValueAsString());
		assertEquals("Firmware Version 1.02", canon.getEntry(0x0007).getValueAsString());
		MakerNoteEntry macroMode = canon.getEntry(0xC101);
		assertEquals("Macro Mode", macroMode.getName());
		assertEquals(1, macroMode.getValueAsLongs().length);
		assertNull(canon.getEntry(0x0001), "the raw camera settings array is replaced by its elements");
	}

	@Test
	@DisplayName("The decoded maker note is listed when iterating EXIF")
	void listedInExifIteration() throws IOException {
		Exif exif = (Exif) Metadata.readMetadata("images/sea.jpg").get(MetadataType.EXIF);
		boolean found = false;
		for (MetadataEntry entry : exif)
			found |= entry.getKey().equals("Nikon Makernote") && !entry.getMetadataEntries().isEmpty();
		assertTrue(found);
	}

	@Test
	@DisplayName("Unknown or truncated maker notes decode to null instead of throwing")
	void unsupportedFormats() {
		assertNull(MakerNoteReader.read(null, 100, true, "Canon"));
		assertNull(MakerNoteReader.read(new byte[1], 100, true, "Canon"));
		assertNull(MakerNoteReader.read("KDK INFO and some binary".getBytes(), 100, true, "Kodak"));
		assertNull(MakerNoteReader.read(new byte[64], 100, true, "Unknown Camera Co."));
		// Offsets relative to the TIFF header can't be resolved without knowing where it was
		assertNull(MakerNoteReader.read(canonIFD(), -1, true, "Canon"));
	}

	@Test
	@DisplayName("A cyclic Olympus sub IFD chain terminates")
	void olympusSubIfdCycle() {
		// "OLYMPUS\0II\3\0" header, then an IFD at 12 whose Equipment (0x2010) entry points back at
		// itself, relative to the maker note start
		ByteBuffer bb = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
		bb.put("OLYMPUS\0II".getBytes()).putShort((short) 3);
		bb.putShort((short) 1);                                    // one entry
		bb.putShort((short) 0x2010).putShort((short) 13).putInt(1).putInt(12); // IFD pointer to itself
		byte[] data = bb.array();

		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> MakerNoteReader.read(data, 100, false, "OLYMPUS"));
	}

	@Test
	@DisplayName("An entry count larger than the data is rejected, not iterated")
	void hugeEntryCount() {
		byte[] data = canonIFD();
		data[0] = (byte) 0x7f; // count 0x7fxx entries in a 14 byte buffer
		assertNull(MakerNoteReader.read(data, 100, true, "Canon"));
	}

	@Test
	@DisplayName("Mutated real maker notes never throw, from decoding or relocation")
	void mutationFuzz() throws IOException {
		for (String image : new String[] {"images/example.jpg", "images/sea.jpg", "images/iptc.tif"}) {
			MakerNoteField field = makerNoteField(image);
			byte[] original = field.getData();
			int offset = field.getOriginalOffset();
			boolean bigEndian = field.isBigEndian();
			String make = field.getMake();
			Random random = new Random(image.hashCode());
			assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
				for (int i = 0; i < 3000; i++) {
					byte[] mutated = original.clone();
					int flips = 1 + random.nextInt(16);
					for (int j = 0; j < flips; j++)
						mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
					MakerNote decoded = MakerNoteReader.read(mutated, offset, bigEndian, make);
					if (decoded != null) {
						for (MakerNoteIFD ifd : decoded.getDirectories())
							for (MakerNoteEntry entry : ifd.getEntries())
								entry.getValueAsString();
					}
					MakerNoteReader.relocate(mutated, offset, offset + random.nextInt(4096) - 2048, bigEndian, make);
				}
			});
		}
	}

	@Test
	@DisplayName("Relocation shifts TIFF relative offsets and leaves self relative maker notes alone")
	void relocation() throws IOException {
		// Canon: offsets are relative to the TIFF header
		byte[] canon = canonIFD();
		byte[] moved = MakerNoteReader.relocate(canon, 100, 300, true, "Canon");
		assertEquals(ByteBuffer.wrap(canon, 10, 4).getInt() + 200, ByteBuffer.wrap(moved, 10, 4).getInt());
		MakerNote before = MakerNoteReader.read(canon, 100, true, "Canon");
		MakerNote after = MakerNoteReader.read(moved, 300, true, "Canon");
		assertEquals(before.getMainDirectory().getEntry(6).getValueAsString(), after.getMainDirectory().getEntry(6).getValueAsString());

		// Nikon type 2 carries its own TIFF header: position independent
		MakerNoteField nikon = makerNoteField("images/sea.jpg");
		byte[] data = nikon.getData();
		assertArrayEquals(data, MakerNoteReader.relocate(data, nikon.getOriginalOffset(), 12345, nikon.isBigEndian(), nikon.getMake()));
	}

	// ----------------------------- helpers -----------------------------

	/**
	 * A minimal big endian Canon maker note: one ASCII "Image Type" entry (tag 6) whose 8 byte value
	 * follows the IFD, stored as an offset relative to a TIFF header {@code base} bytes before it.
	 */
	private static byte[] canonIFD() {
		int base = 100;
		ByteBuffer bb = ByteBuffer.allocate(2 + 12 + 4 + 8).order(ByteOrder.BIG_ENDIAN);
		bb.putShort((short) 1);
		bb.putShort((short) 6).putShort((short) 2).putInt(8).putInt(base + 18); // value at maker note offset 18
		bb.putInt(0);                                                            // next IFD
		bb.put("IMG:TEST".getBytes());
		return bb.array();
	}

	private static MakerNote makerNote(String image) throws IOException {
		Exif exif = (Exif) Metadata.readMetadata(image).get(MetadataType.EXIF);
		return exif.getMakerNote();
	}

	private static MakerNoteField makerNoteField(String image) throws IOException {
		Exif exif = (Exif) Metadata.readMetadata(image).get(MetadataType.EXIF);
		return (MakerNoteField) exif.getExifIFD().getField(ExifTag.MAKER_NOTE);
	}
}
