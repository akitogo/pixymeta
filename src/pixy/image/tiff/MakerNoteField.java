package pixy.image.tiff;

import java.io.IOException;

import pixy.io.RandomAccessOutputStream;
import pixy.meta.exif.ExifTag;
import pixy.meta.exif.makernote.MakerNote;
import pixy.meta.exif.makernote.MakerNoteReader;
import pixy.string.StringUtils;

public class MakerNoteField extends TiffField<byte[]> {
	// Context captured when the field was read, needed to decode the vendor structure
	private final byte[] decodeData;
	private final int originalOffset;
	private final boolean bigEndian;
	private final String make;
	private MakerNote makerNote;
	private boolean isDecoded;

	public MakerNoteField(byte[] data) {
		this(null, data);
	}

	public MakerNoteField(IFD parent, byte[] data) {
		this(parent, data, data, -1, true, null);
	}

	/**
	 * @param parent the EXIF sub IFD this field belongs to
	 * @param data raw maker note bytes, written back unchanged
	 * @param decodeData the bytes to decode from: the maker note optionally followed by the data
	 *        that came after it in the TIFF structure, for maker notes whose values lie past their
	 *        declared length
	 * @param originalOffset offset of the maker note relative to the TIFF header it was read from,
	 *        or -1 if unknown
	 * @param bigEndian byte order of the TIFF structure it was read from
	 * @param make camera make from IFD0, may be null
	 */
	public MakerNoteField(IFD parent, byte[] data, byte[] decodeData, int originalOffset, boolean bigEndian, String make) {
		super(parent, ExifTag.MAKER_NOTE.getValue(), FieldType.EXIF_MAKERNOTE, data.length);
		this.data = data;
		this.decodeData = (decodeData == null) ? data : decodeData;
		this.originalOffset = originalOffset;
		this.bigEndian = bigEndian;
		this.make = make;
	}

	public byte[] getData() {
		return data.clone();
	}

	/**
	 * @return the camera make the maker note layout is chosen by, may be null
	 */
	public String getMake() {
		return make;
	}
	
	/**
	 * @return offset of the maker note relative to the TIFF header it was read from, or -1
	 */
	public int getOriginalOffset() {
		return originalOffset;
	}
	
	/**
	 * @return true if the TIFF structure it was read from is big endian
	 */
	public boolean isBigEndian() {
		return bigEndian;
	}
	
	public String getDataAsString() {
		return StringUtils.byteArrayToHexString(data, 0,  MAX_STRING_REPR_LEN);
	}

	/**
	 * Decodes the vendor specific maker note structure on first use.
	 *
	 * @return the decoded maker note, or null if the format is not supported
	 */
	public synchronized MakerNote getMakerNote() {
		if(!isDecoded) {
			makerNote = MakerNoteReader.read(decodeData, originalOffset, bigEndian, make);
			isDecoded = true;
		}
		return makerNote;
	}

	protected int writeData(RandomAccessOutputStream os, int toOffset) throws IOException {

		if (data.length <= 4) {
			dataOffset = (int)os.getStreamPointer();
			byte[] tmp = new byte[4];
			System.arraycopy(data, 0, tmp, 0, data.length);
			os.write(tmp);
		} else {
			dataOffset = toOffset;
			os.writeInt(toOffset);
			os.seek(toOffset);
			// Keep TIFF header relative offsets inside the maker note pointing at its values
			os.write(MakerNoteReader.relocate(data, originalOffset, toOffset, bigEndian, make));
			toOffset += data.length;
		}
		return toOffset;
	}
}
