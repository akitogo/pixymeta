/**
 * Copyright (C) 2014-2019 by Wen Yu.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Any modifications to this file must keep this entire header intact.
 */

package pixy.image.png;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.zip.InflaterInputStream;

import pixy.util.Reader;

/**
 * Reader for PNG textual chunks: iTXT, zTXT, and tEXT.
 *
 * @author Wen Yu, yuwen_66@yahoo.com
 * @version 1.0 01/01/2013
 */
public class TextReader implements Reader {
	//
	private String keyword;
	private String text;
	private Chunk chunk;

	/**
	 * Upper bound (characters) on the text decompressed from a single zTXt/iTXt chunk. zlib can
	 * amplify roughly 1000:1, so without a cap a small chunk can expand until the heap is
	 * exhausted. Any real textual metadata is far below this limit.
	 */
	private static final int MAX_TEXT_LENGTH = 64 * 1024 * 1024;

	private static void checkTextLimit(StringBuilder sb) throws IOException {
		if(sb.length() > MAX_TEXT_LENGTH)
			throw new IOException("Decompressed PNG text exceeds " + MAX_TEXT_LENGTH + " characters");
	}

	public TextReader() {
		; // Default constructor
	}
	
	public TextReader(Chunk chunk) {
		setInput(chunk);
	}
	
	public String getKeyword() {
		return this.keyword;
	}
	
	public String getText() {
		return text;
	}
	
	// Read text chunks to a String
   	public void read() throws IOException {       
   		StringBuilder sb = new StringBuilder(1024);
   		byte[] data = chunk.getData();
   		
        switch (chunk.getChunkType()) {
		   case ZTXT:
		   {
			   int keyword_len = 0;
			   while(keyword_len < data.length && data[keyword_len]!=0) keyword_len++;
			   if(keyword_len + 2 > data.length)
				   throw new IOException("Malformed zTXt chunk: truncated keyword");
			   this.keyword = new String(data,0,keyword_len,"UTF-8");

			   InflaterInputStream ii = new InflaterInputStream(new ByteArrayInputStream(data,keyword_len+2, data.length-keyword_len-2));
			   InputStreamReader ir = new InputStreamReader(ii,"UTF-8");
               BufferedReader br = new BufferedReader(ir);
			   String read = null;
               while((read=br.readLine()) != null) {
                  sb.append(read);
                  sb.append("\n");
                  checkTextLimit(sb);
               }
			   br.close();

               break;
           }
		   case TEXT:
		   {
			   int keyword_len = 0;
			   while(keyword_len < data.length && data[keyword_len]!=0) keyword_len++;
			   if(keyword_len + 1 > data.length)
				   throw new IOException("Malformed tEXt chunk: truncated keyword");
			   this.keyword = new String(data,0,keyword_len,"UTF-8");
			   sb.append(new String(data,keyword_len+1,data.length-keyword_len-1,"UTF-8"));
			
               break;
		   }
		   case ITXT:
           {
			   // System.setOut(new PrintStream(new File("TextChunk.txt"),"UTF-8"));
               /**
			    * Keyword:             1-79 bytes (character string)
			    * Null separator:      1 byte
			    * Compression flag:    1 byte
			    * Compression method:  1 byte
			    * Language tag:        0 or more bytes (character string)
			    * Null separator:      1 byte
			    * Translated keyword:  0 or more bytes
			    * Null separator:      1 byte
			    * Text:                0 or more bytes
			    */
			   int keyword_len = 0;
			   int trans_keyword_len = 0;
			   int lang_flg_len = 0;
			   boolean compr = false;
			   while(keyword_len < data.length && data[keyword_len]!=0) keyword_len++;
			   sb.append(new String(data,0,keyword_len,"UTF-8"));
			   // keyword NUL, compression flag, compression method, then language-tag NUL: at
			   // least three more bytes must be present or the index walks off the array.
			   if(keyword_len + 3 > data.length)
				   throw new IOException("Malformed iTXt chunk: truncated header");
			   if(data[++keyword_len]==1) compr = true;
			   keyword_len++;//Skip the compression method byte.
               while(++keyword_len < data.length && data[keyword_len]!=0) lang_flg_len++;
			   //////////////////////
			   sb.append("(");
			   if(lang_flg_len>0)
				   sb.append(new String(data,keyword_len-lang_flg_len, lang_flg_len, "UTF-8"));
			   while(++keyword_len < data.length && data[keyword_len]!=0) trans_keyword_len++;
               if(trans_keyword_len>0) {
				   sb.append(" ");
            	   sb.append(new String(data,keyword_len-trans_keyword_len, trans_keyword_len, "UTF-8"));
               }
			   sb.append(")");
			   
			   this.keyword = sb.toString().replaceFirst("\\(\\)", "");
			   
			   sb.setLength(0); // Reset StringBuilder
			   /////////////////////// End of key.
			   if(compr) {//Compressed text
				   InflaterInputStream ii = new InflaterInputStream(new ByteArrayInputStream(data,keyword_len+1, data.length-keyword_len-1));
				   InputStreamReader ir = new InputStreamReader(ii,"UTF-8");
				   BufferedReader br = new BufferedReader(ir);                       
				   String read = null;
				  
				   while((read=br.readLine()) != null) {
					  sb.append(read);
					  sb.append("\n");
					  checkTextLimit(sb);
				   }

				   br.close();
			   } else { //Uncompressed text
				   if(keyword_len + 1 <= data.length)
					   sb.append(new String(data,keyword_len+1,data.length-keyword_len-1,"UTF-8"));
				   sb.append("\n");
			   }

			   if(sb.length() > 0)
				   sb.deleteCharAt(sb.length() - 1);
			   
			   break;
		   }			   

           default:
               throw new IllegalArgumentException("Not a valid textual chunk.");
        }
        this.text = sb.toString();
     }
   	
   	public void setInput(Chunk chunk) {
   		validate(chunk);		
		this.chunk = chunk;
		try {
			read();
		} catch (IOException e) {
			throw new RuntimeException("TextReader: error reading chunk");
		}
   	}
   	
   	private static void validate(Chunk chunk) {
   		if(chunk == null) throw new IllegalArgumentException("Input chunk is null");
		
		if (chunk.getChunkType() != ChunkType.TEXT && chunk.getChunkType() != ChunkType.ZTXT &&
				chunk.getChunkType() != ChunkType.ITXT) {
			throw new IllegalArgumentException("Not a valid textual chunk.");
		}
   	}
}
