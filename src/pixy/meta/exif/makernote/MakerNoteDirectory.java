/**
 * Copyright (C) 2026 by akitogo.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The set of maker note directories and their tag names follow metadata-extractor
 * (https://github.com/drewnoakes/metadata-extractor, Apache License 2.0). See NOTICE.
 */

package pixy.meta.exif.makernote;

import java.util.Map;

/**
 * The vendor specific directory layouts a decoded maker note can contain.
 */
public enum MakerNoteDirectory {
	APPLE,
	CANON,
	CASIO_TYPE1,
	CASIO_TYPE2,
	DJI,
	FUJIFILM,
	KYOCERA,
	LEICA,
	LEICA_TYPE5,
	NIKON_TYPE1,
	NIKON_TYPE2,
	OLYMPUS,
	OLYMPUS_EQUIPMENT,
	OLYMPUS_CAMERA_SETTINGS,
	OLYMPUS_RAW_DEVELOPMENT,
	OLYMPUS_RAW_DEVELOPMENT_2,
	OLYMPUS_IMAGE_PROCESSING,
	OLYMPUS_FOCUS_INFO,
	OLYMPUS_RAW_INFO,
	PANASONIC,
	PENTAX,
	RICOH,
	SAMSUNG_TYPE2,
	SANYO,
	SIGMA,
	SONY_TYPE1,
	SONY_TYPE6;

	// Loaded on first use so only the tables of directories actually seen are built
	private volatile Map<Integer, String> tagNames;

	/**
	 * @return a human readable directory name, for example "Canon Makernote"
	 */
	public String getName() {
		return MakerNoteTagNames.directoryName(this);
	}

	/**
	 * @param tag the maker note tag id
	 * @return the tag name, or null if the tag is not known for this directory
	 */
	public String getTagName(int tag) {
		Map<Integer, String> names = tagNames;
		if(names == null) {
			names = MakerNoteTagNames.tagNames(this);
			tagNames = names;
		}
		return names.get(tag);
	}
}
