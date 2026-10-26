/**
 * Copyright (C) 2014-2019 by Wen Yu.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Any modifications to this file must keep this entire header intact.
 *
 * Change History - most recent changes go on top of previous changes
 *
 * GIFMetq.java
 *
 * Who   Date       Description
 * ====  =========  ======================================================
 * AK    26Oct2026  Added removeMetadata()
 * WY    06Apr2016  Rewrite insertXMPApplicationBlock() to leverage GifXMP
 * WY    16Sep2015  Added insertComment() to insert comment block
 * WY    06Jul2015  Added insertXMP(InputSream, OutputStream, XMP)
 * WY    30Mar2015  Fixed bug with insertXMP() replacing '\0' with ' '
 * WY    13Mar2015  Initial creation
 */

package pixy.meta.gif;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pixy.meta.icc.ICCProfile;
import pixy.meta.image.Comments;
import pixy.meta.xmp.XMP;
import pixy.meta.Metadata;
import pixy.meta.MetadataType;
import pixy.io.IOUtils;
import pixy.util.ArrayUtils;

/**
 * GIF Metadata tool
 * 
 * @author Wen Yu, yuwen_66@yahoo.com
 * @version 1.0 04/16/2014
 */
public class GIFMeta {
	// Define constants
	public static final byte IMAGE_SEPARATOR = 0x2c; // ","
	public static final byte IMAGE_TRAILER = 0x3b; // ";"
	public static final byte EXTENSION_INTRODUCER = 0x21; // "!"
	public static final byte GRAPHIC_CONTROL_LABEL = (byte)0xf9;
	public static final byte APPLICATION_EXTENSION_LABEL = (byte)0xff;
	public static final byte COMMENT_EXTENSION_LABEL = (byte)0xfe;
	public static final byte TEXT_EXTENSION_LABEL = 0x01;
	
	public static final int DISPOSAL_UNSPECIFIED = 0;		
	public static final int DISPOSAL_LEAVE_AS_IS = 1;
	public static final int DISPOSAL_RESTORE_TO_BACKGROUND = 2;
	public static final int DISPOSAL_RESTORE_TO_PREVIOUS = 3;
	
	// Data transfer object for multiple thread support
	private static class DataTransferObject {
		private byte[] header;	
		private byte[] logicalScreenDescriptor;
		private byte[] globalPalette;
		private byte[] imageDescriptor;
		private Map<MetadataType, Metadata> metadataMap;
		private Comments comments;
	}
	
	public static void insertComments(InputStream is, OutputStream os, List<String> comments) throws IOException {
		// Read and copy header and LSD
 		// Create a new data transfer object to hold data
 		DataTransferObject DTO = new DataTransferObject();
 		readHeader(is, DTO);
 		readLSD(is, DTO);
 		os.write(DTO.header);
 		os.write(DTO.logicalScreenDescriptor);
		if((DTO.logicalScreenDescriptor[4]&0x80) == 0x80) {
			int bitsPerPixel = (DTO.logicalScreenDescriptor[4]&0x07)+1;
			int colorsUsed = (1 << bitsPerPixel);
			
			readGlobalPalette(is, colorsUsed, DTO);
			os.write(DTO.globalPalette);
		}		 		
		int numOfComments = comments.size();
		for(int i = 0; i < numOfComments; i++) {
			os.write(EXTENSION_INTRODUCER);
			os.write(COMMENT_EXTENSION_LABEL);
			byte[] commentBytes = comments.get(i).getBytes();
			int numBlocks = commentBytes.length/0xff;
			int leftOver = commentBytes.length % 0xff;
			int offset = 0;
			if(numBlocks > 0) {
				for(int block = 0; block < numBlocks; block++) {
					os.write(0xff);
					os.write(commentBytes, offset, 0xff);
					offset += 0xff;
				}
			}
			if(leftOver > 0) {
				os.write(leftOver);
				os.write(commentBytes, offset, leftOver);
			}
			os.write(0);			
		}
		// Copy the rest of the input stream
 		byte buf[] = new byte[10240]; // 10K
 		int bytesRead = is.read(buf);
 		
 		while(bytesRead != -1) {
 			os.write(buf, 0, bytesRead);
 			bytesRead = is.read(buf);
 		}
	}
	
	public static void insertXMPApplicationBlock(InputStream is, OutputStream os, XMP xmp) throws IOException {
		// Read and copy header and LSD
 		// Create a new data transfer object to hold data
 		DataTransferObject DTO = new DataTransferObject();
 		readHeader(is, DTO);
 		readLSD(is, DTO);
 		os.write(DTO.header);
 		os.write(DTO.logicalScreenDescriptor);

		if((DTO.logicalScreenDescriptor[4]&0x80) == 0x80) {
			int bitsPerPixel = (DTO.logicalScreenDescriptor[4]&0x07)+1;
			int colorsUsed = (1 << bitsPerPixel);
			
			readGlobalPalette(is, colorsUsed, DTO);
			os.write(DTO.globalPalette);
		}
	
		// Insert XMP
 		xmp.write(os);
 		
 		// Copy the rest of the input stream
 		byte[] buf = new byte[10240]; // 10K
 		int bytesRead = is.read(buf);
 		
 		while(bytesRead != -1) {
 			os.write(buf, 0, bytesRead);
 			bytesRead = is.read(buf);
 		}
	}
	
	public static void insertXMPApplicationBlock(InputStream is, OutputStream os, byte[] xmp) throws IOException {
    	insertXMPApplicationBlock(is, os, new GifXMP(xmp));
    }
	
	public static void insertXMPApplicationBlock(InputStream is, OutputStream os, String xmp) throws IOException {
		insertXMPApplicationBlock(is, os, new GifXMP(xmp));
	}
	
	private static boolean readFrame(InputStream is, DataTransferObject DTO) throws IOException {
		// Need to reset some of the fields
		int disposalMethod = -1;
		// End of fields reset
	   
		int image_separator = 0;
	
		do {		   
			image_separator = is.read();
			    
			if(image_separator == -1 || image_separator == 0x3b) { // End of stream 
				return false;
			}
			    
			if (image_separator == 0x21) { // (!) Extension Block
				int func = is.read();
				int len = is.read();
				
				if (func == 0xf9) {
					// Graphic Control Label - identifies the current block as a Graphic Control Extension
					//<<Start of graphic control block>>
					int packedFields = is.read();
					// Determine the disposal method
					disposalMethod = ((packedFields&0x1c)>>2);
					switch(disposalMethod) {
						case DISPOSAL_UNSPECIFIED:
							// Frame disposal method: UNSPECIFIED
						case DISPOSAL_LEAVE_AS_IS:
							// Frame disposal method: LEAVE_AS_IS
						case DISPOSAL_RESTORE_TO_BACKGROUND:
							// Frame disposal method: RESTORE_TO_BACKGROUND
						case DISPOSAL_RESTORE_TO_PREVIOUS:
							// Frame disposal method: RESTORE_TO_PREVIOUS
							break;
						default:
							//throw new RuntimeException("Invalid GIF frame disposal method: " + disposalMethod);
					}
					// Check for transparent color flag
					if((packedFields&0x01) == 0x01) {
						IOUtils.skipFully(is, 2);
						// Transparent GIF
						is.read(); // Transparent color index
						len = is.read();// len=0, block terminator!
					} else {
						IOUtils.skipFully(is, 3);
						len = is.read();// len=0, block terminator!
					}
					// <<End of graphic control block>>
				} else if(func == 0xff) { // Application block
					// Application block
					byte[] xmp_id = {'X', 'M', 'P', ' ', 'D', 'a', 't', 'a', 'X', 'M', 'P' };
					byte[] temp = new byte[0x0B];
					IOUtils.readFully(is, temp);
					// If we have XMP data
					if(Arrays.equals(xmp_id, temp)) {
						ByteArrayOutputStream bout = new ByteArrayOutputStream();
						len = is.read();
						// A block length of -1 means the stream ended inside the sub-block chain;
						// stop rather than feed -1 into new byte[len] (NegativeArraySizeException).
						while(len > 0) {
							bout.write(len);
							temp = new byte[len];
							IOUtils.readFully(is, temp);
							bout.write(temp);
							len = is.read();
						}
						byte[] xmp = bout.toByteArray();
						// Remove the magic trailer - 258 bytes minus the block terminator
						len = xmp.length - 257;
						if(len > 0) // Put it into the Meta data map
							DTO.metadataMap.put(MetadataType.XMP, new GifXMP(ArrayUtils.subArray(xmp, 0, len)));
						len = 0; // We're already at block terminator
					} else 
						len = is.read(); // Block terminator					
				} else if(func == 0xfe) { // Comment block
					// Comment block. A len of -1 (EOF) or 0 (empty) means there is nothing to read;
					// guard before new byte[len] to avoid NegativeArraySizeException on truncation.
					if(len > 0) {
						byte[] comment = new byte[len];
						IOUtils.readFully(is, comment);
						if(DTO.comments == null) DTO.comments = new Comments();
						DTO.comments.addComment(comment);
						// Comment: new String(comment)
					}
					len = is.read();
				}
				// GIF87a specification mentions the repetition of multiple length
				// blocks while GIF89a gives no specific description. For safety, here
				// a while loop is used to check for block terminator!
				while(len > 0) {
					IOUtils.skipFully(is, len);
					len = is.read();// len=0, block terminator!
				}
			}
		} while(image_separator != 0x2c); // ","
		
		// <<Start of new frame>>		
		readImageDescriptor(is, DTO);
		
		int colorsUsed = 1 << ((DTO.logicalScreenDescriptor[4]&0x07)+1);
		
		byte[] localPalette = null;
		
		if((DTO.imageDescriptor[8]&0x80) == 0x80) {
			// A local color map is present
			int bitsPerPixel = (DTO.imageDescriptor[8]&0x07)+1;
			// Colors used in local palette
			colorsUsed = (1<<bitsPerPixel);
			localPalette = new byte[3*colorsUsed];
		    is.read(localPalette);
		}		
	
		if(localPalette == null) localPalette = DTO.globalPalette;	
		is.read(); // LZW Minimum Code Size		
		int len = 0;
		
		while((len = is.read()) > 0) {
			byte[] block = new byte[len];
			is.read(block);
		}
		
		return true;
	}
	
	private static void readGlobalPalette(InputStream is, int num_of_color, DataTransferObject DTO) throws IOException {
		 DTO.globalPalette = new byte[num_of_color*3];
		 is.read(DTO.globalPalette);
	}
	
	private static void readHeader(InputStream is, DataTransferObject DTO) throws IOException {
		DTO.header = new byte[6]; // GIFXXa
		is.read(DTO.header);
	}
	
	private static void readImageDescriptor(InputStream is, DataTransferObject DTO) throws IOException {
		DTO.imageDescriptor = new byte[9];
	    is.read(DTO.imageDescriptor);
	}
	
	private static void readLSD(InputStream is, DataTransferObject DTO) throws IOException {
		DTO.logicalScreenDescriptor = new byte[7];
		is.read(DTO.logicalScreenDescriptor);
	}
	
	public static Map<MetadataType, Metadata> readMetadata(InputStream is) throws IOException {
		// Create a new data transfer object to hold data
		DataTransferObject DTO = new DataTransferObject();
		// Created a Map for the Meta data
		DTO.metadataMap = new HashMap<MetadataType, Metadata>(); 
				
		readHeader(is, DTO);
		readLSD(is, DTO);
		
		// Packed byte
		if((DTO.logicalScreenDescriptor[4]&0x80) == 0x80) {
			// A global color map is present 
			int bitsPerPixel = (DTO.logicalScreenDescriptor[4]&0x07)+1;
			int colorsUsed = (1 << bitsPerPixel);
			
			readGlobalPalette(is, colorsUsed, DTO);			
		}
		
		while(readFrame(is, DTO)) {
			;	
		}
		
		if(DTO.comments != null)
			DTO.metadataMap.put(MetadataType.COMMENT, DTO.comments);		
			
		return DTO.metadataMap;		
	}
	
	public static Map<MetadataType, Metadata> removeMetadata(InputStream is, OutputStream os, MetadataType ... metadataTypes) throws IOException {
		return removeMetadata(new HashSet<MetadataType>(Arrays.asList(metadataTypes)), is, os);
	}
	
	/**
	 * Removes metadata blocks: comment extensions (COMMENT), the "XMP DataXMP" application
	 * extension (XMP) and the "ICCRGBG1012" application extension (ICC_PROFILE). Other types are not
	 * stored in GIF and ignored. Frames and all other blocks are copied unchanged.
	 * 
	 * @return the removed metadata
	 * @throws IOException if the stream is not a GIF or is truncated inside a block
	 */
	public static Map<MetadataType, Metadata> removeMetadata(Set<MetadataType> metadataTypes, InputStream is, OutputStream os) throws IOException {
		Map<MetadataType, Metadata> removed = new HashMap<MetadataType, Metadata>();
		Comments comments = null;
		byte[] header = new byte[6];
		IOUtils.readFully(is, header);
		if(header[0] != 'G' || header[1] != 'I' || header[2] != 'F')
			throw new IOException("Invalid GIF signature");
		byte[] lsd = new byte[7];
		IOUtils.readFully(is, lsd);
		os.write(header);
		os.write(lsd);
		if((lsd[4]&0x80) == 0x80) // Global color table
			copyFully(is, os, 3*(1 << ((lsd[4]&0x07)+1)));
		
		while(true) {
			int introducer = is.read();
			if(introducer == -1) break; // Missing trailer, keep what was there
			if(introducer == IMAGE_TRAILER) {
				os.write(introducer);
				break;
			}
			if(introducer == IMAGE_SEPARATOR) {
				os.write(introducer);
				byte[] descriptor = new byte[9];
				IOUtils.readFully(is, descriptor);
				os.write(descriptor);
				if((descriptor[8]&0x80) == 0x80) // Local color table
					copyFully(is, os, 3*(1 << ((descriptor[8]&0x07)+1)));
				copyFully(is, os, 1); // LZW minimum code size
				copySubBlocks(is, os);
				continue;
			}
			if(introducer != EXTENSION_INTRODUCER)
				throw new IOException("Invalid GIF block introducer: 0x" + Integer.toHexString(introducer));
			int label = readByte(is);
			if(label == (COMMENT_EXTENSION_LABEL&0xff) && metadataTypes.contains(MetadataType.COMMENT)) {
				if(comments == null) comments = new Comments();
				comments.addComment(subBlockData(readSubBlocks(is)));
				continue;
			}
			if(label == (APPLICATION_EXTENSION_LABEL&0xff)) {
				int idLength = readByte(is);
				byte[] id = new byte[idLength];
				IOUtils.readFully(is, id);
				byte[] raw = readSubBlocks(is);
				String application = new String(id, "ISO-8859-1");
				if(application.equals("XMP DataXMP") && metadataTypes.contains(MetadataType.XMP)) {
					// The packet is stored as raw bytes followed by a 257 byte "magic trailer" that
					// makes it parse as sub-blocks, see readFrame()
					if(raw.length > 257) removed.put(MetadataType.XMP, new GifXMP(ArrayUtils.subArray(raw, 0, raw.length - 257)));
					continue;
				}
				if(application.equals("ICCRGBG1012") && metadataTypes.contains(MetadataType.ICC_PROFILE)) {
					byte[] profile = subBlockData(raw);
					if(profile.length > 0) removed.put(MetadataType.ICC_PROFILE, new ICCProfile(profile));
					continue;
				}
				os.write(EXTENSION_INTRODUCER);
				os.write(label);
				os.write(idLength);
				os.write(id);
				os.write(raw);
				os.write(0);
				continue;
			}
			os.write(EXTENSION_INTRODUCER);
			os.write(label);
			copySubBlocks(is, os);
		}
		if(comments != null)
			removed.put(MetadataType.COMMENT, comments);
		
		return removed;
	}
	
	private static int readByte(InputStream is) throws IOException {
		int b = is.read();
		if(b == -1) throw new IOException("Truncated GIF");
		return b;
	}
	
	private static void copyFully(InputStream is, OutputStream os, int length) throws IOException {
		byte[] buf = new byte[length];
		IOUtils.readFully(is, buf);
		os.write(buf);
	}
	
	// Copies a sub-block chain including its 0 terminator
	private static void copySubBlocks(InputStream is, OutputStream os) throws IOException {
		int len;
		while((len = readByte(is)) > 0) {
			os.write(len);
			copyFully(is, os, len);
		}
		os.write(0);
	}
	
	// Reads a sub-block chain, keeping the length bytes but not the 0 terminator
	private static byte[] readSubBlocks(InputStream is) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		int len;
		while((len = readByte(is)) > 0) {
			bout.write(len);
			byte[] block = new byte[len];
			IOUtils.readFully(is, block);
			bout.write(block);
		}
		return bout.toByteArray();
	}
	
	// The payload of a sub-block chain read by readSubBlocks(), without the length bytes
	private static byte[] subBlockData(byte[] raw) {
		ByteArrayOutputStream bout = new ByteArrayOutputStream(raw.length);
		int offset = 0;
		while(offset < raw.length) {
			int len = raw[offset]&0xff;
			bout.write(raw, offset + 1, Math.min(len, raw.length - offset - 1));
			offset += len + 1;
		}
		return bout.toByteArray();
	}
	
	private GIFMeta() {}
}
