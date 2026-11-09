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
 * IPTC.java
 *
 * Who   Date       Description
 * ====  =========  =================================================
 * AK    09Nov2026  Added prepareForWrite(): sorted output with a UTF-8 CodedCharacterSet
 * WY    25Apr2015  Renamed getDataSet() to getDataSets()
 * WY    25Apr2015  Added addDataSets()
 * WY    13Apr2015  Added write()
 */

package pixy.meta.iptc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pixy.meta.Metadata;
import pixy.meta.MetadataEntry;
import pixy.meta.MetadataType;
import pixy.io.IOUtils;

public class IPTC extends Metadata {
	// Obtain a logger instance
	private static final Logger LOGGER = LoggerFactory.getLogger(IPTC.class);
	
	public static void showIPTC(byte[] data) {
		if(data != null && data.length > 0) {
			IPTC iptc = new IPTC(data);
			try {
				iptc.read();
				Iterator<MetadataEntry> iterator = iptc.iterator();
				while(iterator.hasNext()) {
					MetadataEntry item = iterator.next();
					LOGGER.info(item.getKey() + ": " + item.getValue());
					if(item.isMetadataEntryGroup()) {
						String indent = "    ";
						Collection<MetadataEntry> entries = item.getMetadataEntries();
						for(MetadataEntry e : entries) {
							LOGGER.info(indent + e.getKey() + ": " + e.getValue());
						}			
					}					
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	public static void showIPTC(InputStream is) {
		try {
			showIPTC(IOUtils.inputStreamToByteArray(is));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private Map<IPTCTag, List<IPTCDataSet>> datasetMap;
	
	public IPTC() {
		super(MetadataType.IPTC);
		datasetMap =  new TreeMap<IPTCTag, List<IPTCDataSet>>(new IPTCTagComparator());
		isDataRead = true;
	}
	
	public IPTC(byte[] data) {
		super(MetadataType.IPTC, data);
		ensureDataRead();
	}
	
	public void addDataSet(IPTCDataSet dataSet) {
		if(datasetMap != null) {
			IPTCTag tag = dataSet.getTagEnum();
			if(datasetMap.get(tag) == null) {
				List<IPTCDataSet> list = new ArrayList<IPTCDataSet>();
				list.add(dataSet);
				datasetMap.put(tag, list);
			} else if(dataSet.allowMultiple()) {
				datasetMap.get(tag).add(dataSet);
			}
		} else throw new IllegalStateException("DataSet Map is empty");
	}
	
	public void addDataSets(Collection<? extends IPTCDataSet> dataSets) {
		if(datasetMap != null) {
			for(IPTCDataSet dataSet: dataSets) {
				IPTCTag tag = dataSet.getTagEnum();
				if(datasetMap.get(tag) == null) {
					List<IPTCDataSet> list = new ArrayList<IPTCDataSet>();
					list.add(dataSet);
					datasetMap.put(tag, list);
				} else if(dataSet.allowMultiple()) {
					datasetMap.get(tag).add(dataSet);
				}
			}
		} else throw new IllegalStateException("DataSet Map is empty");
	}
	
	/**
	 * Get a string representation of the IPTCDataSet associated with the key
	 *  
	 * @param key the IPTCTag for the IPTCDataSet
	 * @return a String representation of the IPTCDataSet, separated by ";"
	 */	
	public String getAsString(IPTCTag key) {
		// Retrieve the IPTCDataSet list associated with this key
		// Most of the time the list will only contain one item
		List<IPTCDataSet> list = getDataSet(key);
		
		String value = "";
	
		if(list != null) {
			if(list.size() == 1) {
				value = list.get(0).getDataAsString();
			} else {
				for(int i = 0; i < list.size() - 1; i++)
					value += list.get(i).getDataAsString() + ";";
				value += list.get(list.size() - 1).getDataAsString();
			}
		}
			
		return value;
	}
	
	/**
	 * Get a list of IPTCDataSet associated with a key
	 * 
	 * @param key IPTCTag of the DataSet
	 * @return a list of IPTCDataSet associated with the key
	 */
	public List<IPTCDataSet> getDataSet(IPTCTag key) {
		return getDataSets().get(key);
	}
	
	/**
	 * Get all the IPTCDataSet as a map for this IPTC data
	 * 
	 * @return a map with the key for the IPTCDataSet tag and a list of IPTCDataSet as the value
	 */
	public Map<IPTCTag, List<IPTCDataSet>> getDataSets() {
		ensureDataRead();
		return datasetMap;
	}
	
	public Iterator<MetadataEntry> iterator() {
		ensureDataRead();
		if(datasetMap != null){
			// Print multiple entry IPTCDataSet
			Set<Map.Entry<IPTCTag, List<IPTCDataSet>>> entries = datasetMap.entrySet();
			Iterator<Entry<IPTCTag, List<IPTCDataSet>>> iter = entries.iterator();
			return new Iterator<MetadataEntry>() {
				public MetadataEntry next() {
					Entry<IPTCTag, List<IPTCDataSet>> entry = iter.next();
					String key = entry.getKey().getName();
					String value = "";
					
					for(IPTCDataSet item : entry.getValue()) {
						value += ";" + item.getDataAsString();
					}
					
					return new MetadataEntry(key, value.replaceFirst(";", ""));
			    }

			    public boolean hasNext() {
			    	return iter.hasNext();
			    }

			    public void remove() {
			    	throw new UnsupportedOperationException("Removing MetadataEntry is not supported by this Iterator");
			    }
			};
		}
		return Collections.emptyIterator();
	}
	
	public void read() throws IOException {
		if(!isDataRead) {
			int i = 0;
			datasetMap = new TreeMap<IPTCTag, List<IPTCDataSet>>(new IPTCTagComparator());
			if(data.length == 0) { isDataRead = true; return; }
			int tagMarker = data[i];
			while (tagMarker == 0x1c) {
				// A dataset header is 5 bytes: 0x1c marker, record number, tag, 2-byte size. Stop if
				// the buffer is too short rather than indexing past the end.
				if(i + 5 > data.length) break;
				i++;
				int recordNumber = data[i++]&0xff;
				int tag = data[i++]&0xff;
				int recordSize = IOUtils.readUnsignedShortMM(data, i);
				i += 2;
				// The dataset keeps (data, offset, size) and slices it lazily (getData/hashCode), so a
				// size that runs past the buffer would throw later; stop here instead.
				if(i + recordSize > data.length) break;

				if(recordSize > 0) {
					IPTCDataSet dataSet = new IPTCDataSet(recordNumber, tag, recordSize, data, i);
					
					IPTCTag tagEnum = dataSet.getTagEnum();
					if(datasetMap.get(tagEnum) == null) {
						List<IPTCDataSet> list = new ArrayList<IPTCDataSet>();
						list.add(dataSet);
						datasetMap.put(tagEnum, list);
					} else
						datasetMap.get(tagEnum).add(dataSet);
				}
			
				i += recordSize;
				// Sanity check
				if(i >= data.length) break;	
				tagMarker = data[i];							
			}
			// Remove possible duplicates
			for (Map.Entry<IPTCTag, List<IPTCDataSet>> entry : datasetMap.entrySet()){
			    entry.setValue(new ArrayList<IPTCDataSet>(new LinkedHashSet<IPTCDataSet>(entry.getValue())));
			}
			
			isDataRead = true;
		}
	}
	
	public void write(OutputStream os) throws IOException {
		List<IPTCDataSet> all = new ArrayList<IPTCDataSet>();
		for(List<IPTCDataSet> datasets : getDataSets().values())
			all.addAll(datasets);
		for(IPTCDataSet dataset : prepareForWrite(all))
			dataset.write(os);
	}

	/** Envelope record 1:90 value declaring UTF-8 (ISO 2022 escape sequence ESC % G). */
	public static final byte[] UTF8_CODED_CHARACTER_SET = {0x1B, 0x25, 0x47};

	/**
	 * Puts a collection of data sets into the form in which PIXYMETA writes IPTC: sorted by record
	 * and tag number (the IIM standard requires record 1 before record 2), with exactly one
	 * {@link IPTCEnvelopeTag#CODED_CHARACTER_SET CodedCharacterSet} (1:90) data set declaring
	 * UTF-8, which is the encoding {@link IPTCDataSet#IPTCDataSet(IPTCApplicationTag, String)}
	 * uses for strings. Without the declaration readers fall back to Latin-1 or the platform
	 * charset and show non-ASCII text (umlauts, accents, CJK) garbled.
	 * <p>
	 * Application record (record 2) data sets whose bytes are not valid UTF-8 are assumed to be
	 * Latin-1 (ISO-8859-1, the usual default of software that writes no 1:90) and are re-encoded
	 * to UTF-8 so that they stay consistent with the declaration. This matters when existing
	 * IPTC is merged with new data sets ({@code update = true}). Data sets of other records are
	 * copied as they are.
	 *
	 * @param dataSets the data sets to write, in any order
	 * @return a new, sorted list ready to be written
	 */
	public static List<IPTCDataSet> prepareForWrite(Collection<? extends IPTCDataSet> dataSets) {
		List<IPTCDataSet> out = new ArrayList<IPTCDataSet>(dataSets.size() + 1);
		int applicationRecord = IPTCRecord.APPLICATION.getRecordNumber();
		int envelopeRecord = IPTCRecord.ENVELOP.getRecordNumber();
		for(IPTCDataSet dataSet : dataSets) {
			if(dataSet == null) continue;
			if(dataSet.getRecordNumber() == envelopeRecord && dataSet.getTag() == IPTCEnvelopeTag.CODED_CHARACTER_SET.getTag())
				continue; // replaced by the UTF-8 declaration below
			if(dataSet.getRecordNumber() == applicationRecord && dataSet.getTag() != IPTCApplicationTag.RECORD_VERSION.getTag()) {
				byte[] data = dataSet.getData();
				if(!isValidUtf8(data)) {
					byte[] utf8 = new String(data, StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.UTF_8);
					dataSet = new IPTCDataSet(dataSet.getRecordNumber(), dataSet.getTag(), utf8.length, utf8, 0);
				}
			}
			out.add(dataSet);
		}
		out.add(new IPTCDataSet(IPTCRecord.ENVELOP, IPTCEnvelopeTag.CODED_CHARACTER_SET.getTag(), UTF8_CODED_CHARACTER_SET.clone()));
		Collections.sort(out); // stable: data sets of a repeatable tag keep their order
		return out;
	}

	private static boolean isValidUtf8(byte[] data) {
		try {
			StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data));
			return true;
		} catch (CharacterCodingException e) {
			return false;
		}
	}
}
