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
 * PNGMeta.java
 *
 * Who   Date       Description
 * ====  =========  =================================================
 * WY    30Mar2016  Added insertTextChunk()
 * WY    30Mar2016  Changed XMP trailing pi to "end='r'"
 * WY    06Jul2015  Added insertXMP(InputSream, OutputStream, XMP)
 * WY    30Mar2015  Added insertICCProfile()
 * WY    13Mar2015  Initial creation
 */

package pixy.meta.png;

import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.zip.InflaterInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import pixy.meta.png.TIMEChunk;
import pixy.meta.xmp.XMP;
import pixy.image.png.TextBuilder;
import pixy.meta.Metadata;
import pixy.meta.MetadataType;
import pixy.meta.icc.ICCProfile;
import pixy.image.png.Chunk;
import pixy.image.png.ChunkType;
import pixy.image.png.ICCPBuilder;
import pixy.image.png.TextReader;
import pixy.image.png.UnknownChunk;
import pixy.io.IOUtils;
import pixy.string.XMLUtils;
import pixy.string.StringUtils;
import pixy.meta.adobe.IRB;
import pixy.meta.jpeg.JpegExif;

/**
 * PNG image tweaking tool
 *
 * @author Wen Yu, yuwen_66@yahoo.com
 * @version 1.0 10/18/2012
 */
public class PNGMeta {
	
	/** PNG signature constant */
    private static final long SIGNATURE = 0x89504E470D0A1A0AL;

	/**
	 * Upper bound for a single PNG chunk's declared length (bytes). The PNG spec allows up to
	 * 2^31-1, but this is a metadata reader and that ceiling is an easy memory-exhaustion vector
	 * from a tiny file. 128 MB is far beyond any metadata-bearing chunk while still bounding the
	 * worst-case allocation. {@link #readChunks} rejects anything larger.
	 */
	private static final int MAX_CHUNK_LENGTH = 128 * 1024 * 1024;

	/**
	 * Upper bound for the inflated size of a compressed chunk (zTXt/iTXt text, iCCP profile).
	 * zlib can amplify ~1000:1, so an unbounded inflate turns a few KB into gigabytes. Metadata
	 * payloads are far smaller than this cap.
	 */
	static final int MAX_DECOMPRESSED_SIZE = 64 * 1024 * 1024;
	
    // Obtain a logger instance
 	private static final Logger LOGGER = LoggerFactory.getLogger(PNGMeta.class);
 	
   	public static void insertChunk(Chunk customChunk, InputStream is, OutputStream os) throws IOException {
  		insertChunks(is, os, customChunk);
  	}
  	
  	public static void insertChunks(InputStream is, OutputStream os, Chunk... chunks) throws IOException	{
  		List<Chunk> list = readChunks(is);  		
        Collections.addAll(list, chunks);
    	
  		IOUtils.writeLongMM(os, SIGNATURE);
	
        serializeChunks(list, os);
  	}
  	
  	public static void insertChunks(List<Chunk> chunks, InputStream is, OutputStream os) throws IOException	{
  		List<Chunk> list = readChunks(is);  		
        list.addAll(chunks);
    	
  		IOUtils.writeLongMM(os, SIGNATURE);
	
        serializeChunks(list, os);
  	}
  	
  	public static void insertComments(InputStream is, OutputStream os, List<String> comments) throws IOException {
  		// Build tEXt chunk
  		TextBuilder txtBuilder = new TextBuilder(ChunkType.TEXT);
  		int numOfComments = comments.size();
  		Chunk[] chunks = new Chunk[numOfComments];
  		for(int i = 0; i < numOfComments; i++) {
  			chunks[i] = txtBuilder.keyword("Comment").text(comments.get(i)).build();
  		}
  		insertChunks(is, os, chunks);
  	}
  	
  	public static void insertICCProfile(String profile_name, byte[] icc_profile, InputStream is, OutputStream os) throws IOException {
  		ICCPBuilder builder = new ICCPBuilder();
  		builder.name(profile_name);
  		builder.data(icc_profile);
  		insertChunk(builder.build(), is, os);
  	}
  	
  	public static void insertICCProfile(String profile_name, ICC_Profile icc_profile, InputStream is, OutputStream os) throws IOException {
  		insertICCProfile(profile_name, icc_profile.getData(), is, os);
  	}
  	
  	public static void insertTextChunk(ChunkType type, String keyword, String text, InputStream is, OutputStream os) throws IOException {
  		if(type == null || keyword == null || text == null)
  			throw new IllegalArgumentException("Argument(s) are null");
  		
  		insertChunk(new TextBuilder(type).keyword(keyword).text(text).build(), is, os);
  	}
  	
  	public static void insertTextChunks(TextualChunks textualChunks, InputStream is, OutputStream os) throws IOException {
  		if(textualChunks == null) throw new IllegalArgumentException("Argument is null");
  		insertChunks(textualChunks.getChunks(), is, os);
  	}
  	
  	public static void insertXMP(InputStream is, OutputStream os, XMP xmp) throws IOException {
  		insert(is, os, XMLUtils.serializeToString(xmp.getMergedDocument()));
  	}
  	
  	// Add leading and trailing PI
  	public static void insertXMP(InputStream is, OutputStream os, String xmp) throws IOException {
  		Document doc = XMLUtils.createXML(xmp);
		XMLUtils.insertLeadingPI(doc, "xpacket", "begin='' id='W5M0MpCehiHzreSzNTczkc9d'");
		XMLUtils.insertTrailingPI(doc, "xpacket", "end='r'");
		String newXmp = XMLUtils.serializeToString(doc); // DONOT use XMLUtils.serializeToStringLS()
  		insert(is, os, newXmp);
    }
  	
  	private static void insert(InputStream is, OutputStream os, String xmp) throws IOException {
  		// Read all the chunks first
  		List<Chunk> chunks = readChunks(is);
	    ListIterator<Chunk> itr = chunks.listIterator();
	    
	    // Remove old XMP chunk
	    while(itr.hasNext()) {
	    	Chunk chunk = itr.next();
	    	if(chunk.getChunkType() == ChunkType.ITXT) {
	    		TextReader reader = new TextReader(chunk);
	    		// Only the XMP packet is replaced; other iTXt chunks (title, comments...) are kept
				if(reader.getKeyword().equals("XML:com.adobe.xmp")) // We found XMP data
					itr.remove();
	    	}
	    }
	    
	    // Create XMP textual chunk
		Chunk xmpChunk = new TextBuilder(ChunkType.ITXT).keyword("XML:com.adobe.xmp").text(xmp).build();
		// Insert XMP textual chunk into image
	    chunks.add(xmpChunk);
	    
	    IOUtils.writeLongMM(os, SIGNATURE);
	    
        serializeChunks(chunks, os);
    }
  	
   	public static List<Chunk> readChunks(InputStream is) throws IOException {  		
  		List<Chunk> list = new ArrayList<Chunk>();
 		 //Local variables for reading chunks
        int data_len = 0;
        int chunk_type = 0;
        byte[] buf = null;
     
        long signature = IOUtils.readLongMM(is);

        if (signature != SIGNATURE) {
       	 	throw new IOException("Invalid PNG signature");
        }   

        /** Read header */
        /** We are expecting IHDR */
        if ((IOUtils.readIntMM(is) != 13)||(IOUtils.readIntMM(is) != ChunkType.IHDR.getValue())) {
            throw new IOException("Invalid PNG header");
        }     
        
        buf = new byte[13];
        IOUtils.readFully(is, buf, 0, 13);
  
        list.add(new Chunk(ChunkType.IHDR, 13, buf, IOUtils.readUnsignedIntMM(is)));         
      
        while (true) {
        	data_len = IOUtils.readIntMM(is);
	       	chunk_type = IOUtils.readIntMM(is);

	       	if (chunk_type == ChunkType.IEND.getValue()) {
	        	 list.add(new Chunk(ChunkType.IEND, data_len, new byte[0], IOUtils.readUnsignedIntMM(is)));
	       		 break;
	       	}
       		// A chunk length is a signed 32-bit read here. A negative value (top bit set) or an
       		// absurdly large one from a corrupt/malicious file would otherwise be handed straight
       		// to new byte[data_len], throwing NegativeArraySizeException or exhausting the heap
       		// before a single byte is validated. Reject both up front.
       		if (data_len < 0 || data_len > MAX_CHUNK_LENGTH) {
       			throw new IOException("Invalid PNG chunk length: " + (data_len & 0xffffffffL));
       		}
       		ChunkType chunkType = ChunkType.fromInt(chunk_type);
       		buf = new byte[data_len];
       		IOUtils.readFully(is, buf, 0, data_len);
              
       		if (chunkType == ChunkType.UNKNOWN)
       			list.add(new UnknownChunk(data_len, chunk_type, buf, IOUtils.readUnsignedIntMM(is)));
       		else
       			list.add(new Chunk(chunkType, data_len, buf, IOUtils.readUnsignedIntMM(is)));
        }
        
        return list;
  	}
   	
   	private static byte[] readICCProfile(byte[] buf) throws IOException {
		int profileName_len = 0;
		// Guard the null-separator scan: a chunk with no NUL terminator would run off the end.
		while(profileName_len < buf.length && buf[profileName_len] != 0) profileName_len++;
		if(profileName_len + 2 > buf.length)
			throw new IOException("Malformed iCCP chunk: missing profile name separator");
		String profileName = new String(buf, 0, profileName_len, "UTF-8");

		InflaterInputStream ii = new InflaterInputStream(new ByteArrayInputStream(buf, profileName_len + 2, buf.length - profileName_len - 2));
		LOGGER.info("ICCProfile name: {}", profileName);

		// Cap the inflated output so a compression bomb cannot exhaust the heap.
		byte[] icc_profile = readFullyCapped(ii, MAX_DECOMPRESSED_SIZE, "iCCP profile");
		LOGGER.info("ICCProfile length: {}", icc_profile.length);

		return icc_profile;
 	}

	/**
	 * Reads an input stream fully into a byte array, aborting with an {@link IOException} once the
	 * accumulated output exceeds {@code maxBytes}. Used to bound decompression of untrusted data.
	 */
	static byte[] readFullyCapped(InputStream is, int maxBytes, String what) throws IOException {
		java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int total = 0;
		int count;
		while ((count = is.read(buf)) > 0) {
			total += count;
			if (total < 0 || total > maxBytes)
				throw new IOException("Decompressed " + what + " exceeds " + maxBytes + " bytes");
			bout.write(buf, 0, count);
		}
		return bout.toByteArray();
	}
  	
   	public static Map<MetadataType, Metadata> readMetadata(InputStream is) throws IOException {
		Map<MetadataType, Metadata> metadataMap = readMetadata(readChunks(is));
		is.close();
		
		return metadataMap;
	}
   	
   	// Interprets the metadata held by the given chunks
   	private static Map<MetadataType, Metadata> readMetadata(List<Chunk> chunks) throws IOException {
		Map<MetadataType, Metadata> metadataMap = new HashMap<MetadataType, Metadata>();
		Iterator<Chunk> iter = chunks.iterator();
		TextualChunks textualChunk = null;
		while (iter.hasNext()) {
			Chunk chunk = iter.next();
			ChunkType type = chunk.getChunkType();
			long length = chunk.getLength();
			if(type == ChunkType.ICCP)
				metadataMap.put(MetadataType.ICC_PROFILE, new ICCProfile(readICCProfile(chunk.getData())));
			else if(type == ChunkType.TEXT || type == ChunkType.ITXT || type == ChunkType.ZTXT) {
				if(textualChunk == null)
					textualChunk = new TextualChunks();
				textualChunk.addChunk(chunk);			
			} else if(type == ChunkType.TIME) {
				metadataMap.put(MetadataType.PNG_TIME, new TIMEChunk(chunk));
			} else if(type == ChunkType.EXIF) {
				metadataMap.put(MetadataType.EXIF, new JpegExif(chunk.getData()));
			}
			
			LOGGER.info("{} ({}) | {} bytes | 0x{} (CRC)", type.getName(), type.getAttribute(), length, Long.toHexString(chunk.getCRC()));
		}
		
		if(textualChunk != null) {
			metadataMap.put(MetadataType.PNG_TEXTUAL, textualChunk);
			
			// We may find XMP data inside iTXT
			Map<String, String> keyValMap = textualChunk.getKeyValMap();
			
			for (Map.Entry<String, String> entry : keyValMap.entrySet()) {
				if(entry.getKey().equals("XML:com.adobe.xmp"))
					metadataMap.put(MetadataType.XMP, new PngXMP(entry.getValue()));
				else if (entry.getKey().equals("Raw profile type iptc")) {
					// Experimental implementation due to limited information
					String[] iptc = entry.getValue().trim().split("\n");
                                        if(iptc.length >= 3) {
						try {
							String name = iptc[0].trim();
							int length = Integer.parseInt(iptc[1].trim());
							StringBuffer sb = new StringBuffer();
							for(int i = 2; i < iptc.length; i++) {
								sb.append(iptc[i]);
							}
							byte[] data = StringUtils.hexStringToByteArray(sb.toString());
							metadataMap.put(MetadataType.PHOTOSHOP_IRB, new IRB(data));
						} catch (Exception e) {
							LOGGER.error("Error while converting IPTC from zTXT {}", e.getMessage());
						}
					}
				} else if (entry.getKey().equals("Raw profile type exif")) {
					// Experimental implementation due to limited information
					String[] exif = entry.getValue().trim().split("\n");
                                        if(exif.length >= 3) {
						try {
							String name = exif[0].trim();
							int length = Integer.parseInt(exif[1].trim());
							StringBuffer sb = new StringBuffer();
							for(int i = 2; i < exif.length; i++) {
								sb.append(exif[i]);
							}
							// Need to strip out exif header before passing to JpegExif constructor
							byte[] data = StringUtils.hexStringToByteArray(sb.toString().substring(12));
							metadataMap.put(MetadataType.EXIF, new JpegExif(data));
						} catch (Exception e) {
							LOGGER.error("Error while converting EXIF from zTXT {}", e.getMessage());
						}
					}
				}
			}
		}
		
		return metadataMap;
	}
	
	public static Map<MetadataType, Metadata> removeMetadata(InputStream is, OutputStream os, MetadataType ... metadataTypes) throws IOException {
		return removeMetadata(new HashSet<MetadataType>(Arrays.asList(metadataTypes)), is, os);
	}
	
	/**
	 * Removes metadata chunks. The types map to chunks as follows:
	 * <ul>
	 * <li>EXIF: eXIf, and "Raw profile type exif" text chunks</li>
	 * <li>XMP: the "XML:com.adobe.xmp" iTXt chunk</li>
	 * <li>ICC_PROFILE: iCCP</li>
	 * <li>IPTC, PHOTOSHOP_IRB: "Raw profile type iptc" text chunks</li>
	 * <li>COMMENT: "Comment" text chunks</li>
	 * <li>PNG_TEXTUAL: all tEXt, zTXt and iTXt chunks, including the ones above</li>
	 * <li>PNG_TIME: tIME</li>
	 * </ul>
	 * Image data and all other chunks are copied unchanged.
	 * 
	 * @return the removed metadata, as readMetadata() reports it, or an empty map if the removed
	 *         chunks are too corrupt to parse
	 */
	public static Map<MetadataType, Metadata> removeMetadata(Set<MetadataType> metadataTypes, InputStream is, OutputStream os) throws IOException {
		List<Chunk> chunks = readChunks(is);
		List<Chunk> removed = new ArrayList<Chunk>();
		Iterator<Chunk> iter = chunks.iterator();
		while(iter.hasNext()) {
			Chunk chunk = iter.next();
			if(isRemoved(chunk, metadataTypes)) {
				removed.add(chunk);
				iter.remove();
			}
		}
		IOUtils.writeLongMM(os, SIGNATURE);
		serializeChunks(chunks, os);
		
		try {
			return readMetadata(removed);
		} catch(IOException e) {
			// The image was written; corrupt metadata that was removed (e.g. an iCCP chunk that
			// does not inflate) just can't be reported back
			LOGGER.warn("Removed PNG metadata could not be parsed: {}", e.getMessage());
			return Collections.emptyMap();
		}
	}
	
	private static boolean isRemoved(Chunk chunk, Set<MetadataType> types) {
		ChunkType type = chunk.getChunkType();
		switch(type) {
			case EXIF: return types.contains(MetadataType.EXIF);
			case ICCP: return types.contains(MetadataType.ICC_PROFILE);
			case TIME: return types.contains(MetadataType.PNG_TIME);
			case TEXT:
			case ZTXT:
			case ITXT:
				if(types.contains(MetadataType.PNG_TEXTUAL)) return true;
				String keyword = textKeyword(chunk);
				if(keyword.equals("XML:com.adobe.xmp")) return types.contains(MetadataType.XMP);
				if(keyword.equals("Raw profile type exif")) return types.contains(MetadataType.EXIF);
				if(keyword.equals("Raw profile type iptc")) return types.contains(MetadataType.IPTC) || types.contains(MetadataType.PHOTOSHOP_IRB);
				if(keyword.equals("Comment")) return types.contains(MetadataType.COMMENT);
				return false;
			default:
				return false;
		}
	}
	
	// The keyword of tEXt, zTXt and iTXt chunks is the Latin-1 text up to the first NUL
	private static String textKeyword(Chunk chunk) {
		byte[] data = chunk.getData();
		int length = 0;
		while(length < data.length && data[length] != 0) length++;
		return new String(data, 0, length, StandardCharsets.ISO_8859_1);
	}
  	
	public static List<Chunk> removeChunks(List<Chunk> chunks, ChunkType chunkType) {
  		
  		Iterator<Chunk> iter = chunks.listIterator();
   	
   		while(iter.hasNext()) {
   			
   			Chunk chunk = iter.next();
   		
   			if (chunk.getChunkType() == chunkType) {   				
   				iter.remove();
   			}   			
   		}
   		
   		return chunks;  		
  	}
   	
   	/**
   	 * Removes chunks which have the same ChunkType values from the chunkEnumSet.
   	 * 
   	 * @param chunks a list of chunks to be checked.
   	 * @param chunkTypeSet a set of ChunkType (better use a HashSet instead of EnumSet for performance).
   	 * @return a list of chunks with the specified chunks removed if any.
   	 */
   	
   	public static List<Chunk> removeChunks(List<Chunk> chunks, Set<ChunkType> chunkTypeSet) {
  		
  		Iterator<Chunk> iter = chunks.listIterator();
   	
   		while(iter.hasNext()) {
   			
   			Chunk chunk = iter.next();
   		
   			if (chunkTypeSet.contains(chunk.getChunkType())) {   				
   				iter.remove();
   			}   			
   		}
   		
   		return chunks;  		
  	}
  	
   	public static void serializeChunks(List<Chunk> chunks, OutputStream os) throws IOException {
  		
  		Collections.sort(chunks);
  	    
  		for(Chunk chunk : chunks) {
        	chunk.write(os);
        }
  	}
  	
  	private PNGMeta() {}
}
