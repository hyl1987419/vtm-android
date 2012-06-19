/*
 * Copyright 2010, 2011, 2012 mapsforge.org
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.mapsforge.mapdatabase.mapfile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.mapsforge.core.MercatorProjection;
import org.mapsforge.core.Tag;
import org.mapsforge.core.Tile;
import org.mapsforge.mapdatabase.FileOpenResult;
import org.mapsforge.mapdatabase.IMapDatabase;
import org.mapsforge.mapdatabase.IMapDatabaseCallback;
import org.mapsforge.mapdatabase.mapfile.header.MapFileHeader;
import org.mapsforge.mapdatabase.mapfile.header.MapFileInfo;
import org.mapsforge.mapdatabase.mapfile.header.SubFileParameter;

/**
 * A class for reading binary map files.
 * <p>
 * This class is not thread-safe. Each thread should use its own instance.
 * 
 * @see <a href="http://code.google.com/p/mapsforge/wiki/SpecificationBinaryMapFile">Specification</a>
 */
public class MapDatabase implements IMapDatabase {
	/**
	 * Bitmask to extract the block offset from an index entry.
	 */
	private static final long BITMASK_INDEX_OFFSET = 0x7FFFFFFFFFL;

	/**
	 * Bitmask to extract the water information from an index entry.
	 */
	private static final long BITMASK_INDEX_WATER = 0x8000000000L;

	/**
	 * Debug message prefix for the block signature.
	 */
	private static final String DEBUG_SIGNATURE_BLOCK = "block signature: ";

	/**
	 * Debug message prefix for the POI signature.
	 */
	// private static final String DEBUG_SIGNATURE_POI = "POI signature: ";

	/**
	 * Debug message prefix for the way signature.
	 */
	private static final String DEBUG_SIGNATURE_WAY = "way signature: ";

	/**
	 * Amount of cache blocks that the index cache should store.
	 */
	private static final int INDEX_CACHE_SIZE = 64;

	/**
	 * Error message for an invalid first way offset.
	 */
	private static final String INVALID_FIRST_WAY_OFFSET = "invalid first way offset: ";

	private static final Logger LOG = Logger.getLogger(MapDatabase.class.getName());

	/**
	 * Maximum way nodes sequence length which is considered as valid.
	 */
	private static final int MAXIMUM_WAY_NODES_SEQUENCE_LENGTH = 8192;

	/**
	 * Maximum number of map objects in the zoom table which is considered as valid.
	 */
	private static final int MAXIMUM_ZOOM_TABLE_OBJECTS = 65536;

	/**
	 * Bitmask for the optional POI feature "elevation".
	 */
	private static final int POI_FEATURE_ELEVATION = 0x20;

	/**
	 * Bitmask for the optional POI feature "house number".
	 */
	private static final int POI_FEATURE_HOUSE_NUMBER = 0x40;

	/**
	 * Bitmask for the optional POI feature "name".
	 */
	private static final int POI_FEATURE_NAME = 0x80;

	/**
	 * Bitmask for the POI layer.
	 */
	private static final int POI_LAYER_BITMASK = 0xf0;

	/**
	 * Bit shift for calculating the POI layer.
	 */
	private static final int POI_LAYER_SHIFT = 4;

	/**
	 * Bitmask for the number of POI tags.
	 */
	private static final int POI_NUMBER_OF_TAGS_BITMASK = 0x0f;

	private static final String READ_ONLY_MODE = "r";

	/**
	 * Length of the debug signature at the beginning of each block.
	 */
	private static final byte SIGNATURE_LENGTH_BLOCK = 32;

	/**
	 * Length of the debug signature at the beginning of each POI.
	 */
	private static final byte SIGNATURE_LENGTH_POI = 32;

	/**
	 * Length of the debug signature at the beginning of each way.
	 */
	private static final byte SIGNATURE_LENGTH_WAY = 32;

	/**
	 * Bitmask for the optional way data blocks byte.
	 */
	private static final int WAY_FEATURE_DATA_BLOCKS_BYTE = 0x08;

	/**
	 * Bitmask for the optional way double delta encoding.
	 */
	private static final int WAY_FEATURE_DOUBLE_DELTA_ENCODING = 0x04;

	/**
	 * Bitmask for the optional way feature "house number".
	 */
	private static final int WAY_FEATURE_HOUSE_NUMBER = 0x40;

	/**
	 * Bitmask for the optional way feature "label position".
	 */
	private static final int WAY_FEATURE_LABEL_POSITION = 0x10;

	/**
	 * Bitmask for the optional way feature "name".
	 */
	private static final int WAY_FEATURE_NAME = 0x80;

	/**
	 * Bitmask for the optional way feature "reference".
	 */
	private static final int WAY_FEATURE_REF = 0x20;

	/**
	 * Bitmask for the way layer.
	 */
	private static final int WAY_LAYER_BITMASK = 0xf0;

	/**
	 * Bit shift for calculating the way layer.
	 */
	private static final int WAY_LAYER_SHIFT = 4;

	/**
	 * Bitmask for the number of way tags.
	 */
	private static final int WAY_NUMBER_OF_TAGS_BITMASK = 0x0f;

	private IndexCache mDatabaseIndexCache;
	private long mFileSize;
	private boolean mDebugFile;
	private RandomAccessFile mInputFile;
	private MapFileHeader mMapFileHeader;
	private ReadBuffer mReadBuffer;
	private String mSignatureBlock;
	private String mSignaturePoi;
	private String mSignatureWay;
	private int mTileLatitude;
	private int mTileLongitude;
	private int[] mIntBuffer;

	private float[] mWayNodes = new float[100000];
	private int mWayNodePosition;

	/*
	 * (non-Javadoc)
	 * @see org.mapsforge.map.reader.IMapDatabase#closeFile()
	 */
	@Override
	public void closeFile() {
		try {
			mMapFileHeader = null;

			if (mDatabaseIndexCache != null) {
				mDatabaseIndexCache.destroy();
				mDatabaseIndexCache = null;
			}

			if (mInputFile != null) {
				mInputFile.close();
				mInputFile = null;
			}

			mReadBuffer = null;
		} catch (IOException e) {
			LOG.log(Level.SEVERE, null, e);
		}
	}

	private int minLat, minLon;

	/*
	 * (non-Javadoc)
	 * @see org.mapsforge.map.reader.IMapDatabase#executeQuery(org.mapsforge.core.Tile,
	 * org.mapsforge.map.reader.MapDatabaseCallback)
	 */
	@Override
	public void executeQuery(Tile tile, IMapDatabaseCallback mapDatabaseCallback) {
		if (mIntBuffer == null)
			mIntBuffer = new int[MAXIMUM_WAY_NODES_SEQUENCE_LENGTH * 2];

		mWayNodePosition = 0;

		// if (tile.zoomLevel < 10) {
		// // reduce small nodes with distance smaller min pixel
		// int min = 1;
		// long cx = tile.getPixelX() + (Tile.TILE_SIZE >> 1);
		// long cy = tile.getPixelY() + (Tile.TILE_SIZE >> 1);
		// double l1 = MercatorProjection.pixelXToLongitude(cx, tile.zoomLevel);
		// double l2 = MercatorProjection.pixelXToLongitude(cx + min, tile.zoomLevel);
		// minLon = (int) Math.abs((l1 * 1000000.0) - (l2 * 1000000.0));
		// l1 = MercatorProjection.pixelYToLatitude(cy, tile.zoomLevel);
		// l2 = MercatorProjection.pixelYToLatitude(cy + min, tile.zoomLevel);
		// minLat = (int) Math.abs((l1 * 1000000.0) - (l2 * 1000000.0));
		// } else {
		minLat = 0;
		minLon = 0;
		// }

		try {
			prepareExecution();
			QueryParameters queryParameters = new QueryParameters();
			queryParameters.queryZoomLevel = mMapFileHeader.getQueryZoomLevel(tile.zoomLevel);
			// get and check the sub-file for the query zoom level
			SubFileParameter subFileParameter = mMapFileHeader.getSubFileParameter(queryParameters.queryZoomLevel);
			if (subFileParameter == null) {
				LOG.warning("no sub-file for zoom level: " + queryParameters.queryZoomLevel);
				return;
			}

			QueryCalculations.calculateBaseTiles(queryParameters, tile, subFileParameter);
			QueryCalculations.calculateBlocks(queryParameters, subFileParameter);
			processBlocks(mapDatabaseCallback, queryParameters, subFileParameter);
		} catch (IOException e) {
			LOG.log(Level.SEVERE, null, e);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.mapsforge.map.reader.IMapDatabase#getMapFileInfo()
	 */
	@Override
	public MapFileInfo getMapFileInfo() {
		if (mMapFileHeader == null) {
			throw new IllegalStateException("no map file is currently opened");
		}
		return mMapFileHeader.getMapFileInfo();
	}

	/*
	 * (non-Javadoc)
	 * @see org.mapsforge.map.reader.IMapDatabase#hasOpenFile()
	 */
	@Override
	public boolean hasOpenFile() {
		return mInputFile != null;
	}

	/*
	 * (non-Javadoc)
	 * @see org.mapsforge.map.reader.IMapDatabase#openFile(java.io.File)
	 */
	@Override
	public FileOpenResult openFile(File mapFile) {
		try {
			if (mapFile == null) {
				throw new IllegalArgumentException("mapFile must not be null");
			}

			// make sure to close any previously opened file first
			closeFile();

			// check if the file exists and is readable
			if (!mapFile.exists()) {
				return new FileOpenResult("file does not exist: " + mapFile);
			} else if (!mapFile.isFile()) {
				return new FileOpenResult("not a file: " + mapFile);
			} else if (!mapFile.canRead()) {
				return new FileOpenResult("cannot read file: " + mapFile);
			}

			// open the file in read only mode
			mInputFile = new RandomAccessFile(mapFile, READ_ONLY_MODE);
			mFileSize = mInputFile.length();
			mReadBuffer = new ReadBuffer(mInputFile);

			mMapFileHeader = new MapFileHeader();
			FileOpenResult fileOpenResult = mMapFileHeader.readHeader(mReadBuffer, mFileSize);
			if (!fileOpenResult.isSuccess()) {
				closeFile();
				return fileOpenResult;
			}

			return FileOpenResult.SUCCESS;
		} catch (IOException e) {
			LOG.log(Level.SEVERE, null, e);
			// make sure that the file is closed
			closeFile();
			return new FileOpenResult(e.getMessage());
		}
	}

	/**
	 * Logs the debug signatures of the current way and block.
	 */
	private void logDebugSignatures() {
		if (mDebugFile) {
			LOG.warning(DEBUG_SIGNATURE_WAY + mSignatureWay);
			LOG.warning(DEBUG_SIGNATURE_BLOCK + mSignatureBlock);
		}
	}

	private void prepareExecution() {
		if (mDatabaseIndexCache == null) {
			mDatabaseIndexCache = new IndexCache(mInputFile, INDEX_CACHE_SIZE);
		}
	}

	/**
	 * Processes a single block and executes the callback functions on all map elements.
	 * 
	 * @param queryParameters
	 *            the parameters of the current query.
	 * @param subFileParameter
	 *            the parameters of the current map file.
	 * @param mapDatabaseCallback
	 *            the callback which handles the extracted map elements.
	 */
	private void processBlock(QueryParameters queryParameters, SubFileParameter subFileParameter,
			IMapDatabaseCallback mapDatabaseCallback) {
		if (!processBlockSignature()) {
			return;
		}

		int[][] zoomTable = readZoomTable(subFileParameter);
		if (zoomTable == null) {
			return;
		}
		int zoomTableRow = queryParameters.queryZoomLevel - subFileParameter.zoomLevelMin;
		int poisOnQueryZoomLevel = zoomTable[zoomTableRow][0];
		int waysOnQueryZoomLevel = zoomTable[zoomTableRow][1];

		// get the relative offset to the first stored way in the block
		int firstWayOffset = mReadBuffer.readUnsignedInt();
		if (firstWayOffset < 0) {
			LOG.warning(INVALID_FIRST_WAY_OFFSET + firstWayOffset);
			if (mDebugFile) {
				LOG.warning(DEBUG_SIGNATURE_BLOCK + mSignatureBlock);
			}
			return;
		}

		// add the current buffer position to the relative first way offset
		firstWayOffset += mReadBuffer.getBufferPosition();
		if (firstWayOffset > mReadBuffer.getBufferSize()) {
			LOG.warning(INVALID_FIRST_WAY_OFFSET + firstWayOffset);
			if (mDebugFile) {
				LOG.warning(DEBUG_SIGNATURE_BLOCK + mSignatureBlock);
			}
			return;
		}

		if (!processPOIs(mapDatabaseCallback, poisOnQueryZoomLevel)) {
			return;
		}

		// finished reading POIs, check if the current buffer position is valid
		if (mReadBuffer.getBufferPosition() > firstWayOffset) {
			LOG.warning("invalid buffer position: " + mReadBuffer.getBufferPosition());
			if (mDebugFile) {
				LOG.warning(DEBUG_SIGNATURE_BLOCK + mSignatureBlock);
			}
			return;
		}

		// move the pointer to the first way
		mReadBuffer.setBufferPosition(firstWayOffset);
		if (!processWays(queryParameters, mapDatabaseCallback, waysOnQueryZoomLevel)) {
			return;
		}

	}

	private void processBlocks(IMapDatabaseCallback mapDatabaseCallback, QueryParameters queryParameters,
			SubFileParameter subFileParameter) throws IOException {
		boolean queryIsWater = true;
		// boolean queryReadWaterInfo = false;

		// read and process all blocks from top to bottom and from left to right
		for (long row = queryParameters.fromBlockY; row <= queryParameters.toBlockY; ++row) {
			for (long column = queryParameters.fromBlockX; column <= queryParameters.toBlockX; ++column) {

				// calculate the actual block number of the needed block in the file
				long blockNumber = row * subFileParameter.blocksWidth + column;

				// get the current index entry
				long currentBlockIndexEntry = mDatabaseIndexCache.getIndexEntry(subFileParameter, blockNumber);

				// check if the current query would still return a water tile
				if (queryIsWater) {
					// check the water flag of the current block in its index entry
					queryIsWater &= (currentBlockIndexEntry & BITMASK_INDEX_WATER) != 0;
					// queryReadWaterInfo = true;
				}

				// get and check the current block pointer
				long currentBlockPointer = currentBlockIndexEntry & BITMASK_INDEX_OFFSET;
				if (currentBlockPointer < 1 || currentBlockPointer > subFileParameter.subFileSize) {
					LOG.warning("invalid current block pointer: " + currentBlockPointer);
					LOG.warning("subFileSize: " + subFileParameter.subFileSize);
					return;
				}

				long nextBlockPointer;
				// check if the current block is the last block in the file
				if (blockNumber + 1 == subFileParameter.numberOfBlocks) {
					// set the next block pointer to the end of the file
					nextBlockPointer = subFileParameter.subFileSize;
				} else {
					// get and check the next block pointer
					nextBlockPointer = mDatabaseIndexCache.getIndexEntry(subFileParameter, blockNumber + 1)
							& BITMASK_INDEX_OFFSET;
					if (nextBlockPointer < 1 || nextBlockPointer > subFileParameter.subFileSize) {
						LOG.warning("invalid next block pointer: " + nextBlockPointer);
						LOG.warning("sub-file size: " + subFileParameter.subFileSize);
						return;
					}
				}

				// calculate the size of the current block
				int currentBlockSize = (int) (nextBlockPointer - currentBlockPointer);
				if (currentBlockSize < 0) {
					LOG.warning("current block size must not be negative: " + currentBlockSize);
					return;
				} else if (currentBlockSize == 0) {
					// the current block is empty, continue with the next block
					continue;
				} else if (currentBlockSize > ReadBuffer.MAXIMUM_BUFFER_SIZE) {
					// the current block is too large, continue with the next block
					LOG.warning("current block size too large: " + currentBlockSize);
					continue;
				} else if (currentBlockPointer + currentBlockSize > mFileSize) {
					LOG.warning("current block largher than file size: " + currentBlockSize);
					return;
				}

				// seek to the current block in the map file
				mInputFile.seek(subFileParameter.startAddress + currentBlockPointer);

				// read the current block into the buffer
				if (!mReadBuffer.readFromFile(currentBlockSize)) {
					// skip the current block
					LOG.warning("reading current block has failed: " + currentBlockSize);
					return;
				}

				// calculate the top-left coordinates of the underlying tile
				double tileLatitudeDeg = MercatorProjection.tileYToLatitude(subFileParameter.boundaryTileTop + row,
						subFileParameter.baseZoomLevel);
				double tileLongitudeDeg = MercatorProjection.tileXToLongitude(subFileParameter.boundaryTileLeft
						+ column, subFileParameter.baseZoomLevel);
				mTileLatitude = (int) (tileLatitudeDeg * 1000000);
				mTileLongitude = (int) (tileLongitudeDeg * 1000000);

				try {
					processBlock(queryParameters, subFileParameter, mapDatabaseCallback);
				} catch (ArrayIndexOutOfBoundsException e) {
					LOG.log(Level.SEVERE, null, e);
				}
			}
		}

		// the query is finished, was the water flag set for all blocks?
		// if (queryIsWater && queryReadWaterInfo) {
		// Tag[] tags = new Tag[1];
		// tags[0] = TAG_NATURAL_WATER;
		//
		// System.arraycopy(WATER_TILE_COORDINATES, 0, mWayNodes, mWayNodePosition, 8);
		// mWayNodePosition += 8;
		// mapDatabaseCallback.renderWaterBackground(tags, wayDataContainer);
		// }

	}

	/**
	 * Processes the block signature, if present.
	 * 
	 * @return true if the block signature could be processed successfully, false otherwise.
	 */
	private boolean processBlockSignature() {
		if (mDebugFile) {
			// get and check the block signature
			mSignatureBlock = mReadBuffer.readUTF8EncodedString(SIGNATURE_LENGTH_BLOCK);
			if (!mSignatureBlock.startsWith("###TileStart")) {
				LOG.warning("invalid block signature: " + mSignatureBlock);
				return false;
			}
		}
		return true;
	}

	/**
	 * Processes the given number of POIs.
	 * 
	 * @param mapDatabaseCallback
	 *            the callback which handles the extracted POIs.
	 * @param numberOfPois
	 *            how many POIs should be processed.
	 * @return true if the POIs could be processed successfully, false otherwise.
	 */
	private boolean processPOIs(IMapDatabaseCallback mapDatabaseCallback, int numberOfPois) {
		// List<Tag> tags = new ArrayList<Tag>();
		Tag[] poiTags = mMapFileHeader.getMapFileInfo().poiTags;
		Tag[] tags = null;

		for (int elementCounter = numberOfPois; elementCounter != 0; --elementCounter) {
			if (mDebugFile) {
				// get and check the POI signature
				mSignaturePoi = mReadBuffer.readUTF8EncodedString(SIGNATURE_LENGTH_POI);
				if (!mSignaturePoi.startsWith("***POIStart")) {
					LOG.warning("invalid POI signature: " + mSignaturePoi);
					LOG.warning(DEBUG_SIGNATURE_BLOCK + mSignatureBlock);
					return false;
				}
			}

			// get the POI latitude offset (VBE-S)
			int latitude = mTileLatitude + mReadBuffer.readSignedInt();

			// get the POI longitude offset (VBE-S)
			int longitude = mTileLongitude + mReadBuffer.readSignedInt();

			// get the special byte which encodes multiple flags
			byte specialByte = mReadBuffer.readByte();

			// bit 1-4 represent the layer
			byte layer = (byte) ((specialByte & POI_LAYER_BITMASK) >>> POI_LAYER_SHIFT);
			// bit 5-8 represent the number of tag IDs
			byte numberOfTags = (byte) (specialByte & POI_NUMBER_OF_TAGS_BITMASK);

			// boolean changed = false;

			if (numberOfTags != 0) {
				tags = mReadBuffer.readTags(poiTags, numberOfTags);
				// changed = true;
			}
			if (tags == null)
				return false;

			// get the feature bitmask (1 byte)
			byte featureByte = mReadBuffer.readByte();

			// bit 1-3 enable optional features
			boolean featureName = (featureByte & POI_FEATURE_NAME) != 0;
			boolean featureHouseNumber = (featureByte & POI_FEATURE_HOUSE_NUMBER) != 0;
			boolean featureElevation = (featureByte & POI_FEATURE_ELEVATION) != 0;

			// check if the POI has a name
			if (featureName) {
				mReadBuffer.getPositionAndSkip();
			}

			// check if the POI has a house number
			if (featureHouseNumber) {
				mReadBuffer.getPositionAndSkip();
			}

			// check if the POI has an elevation
			if (featureElevation) {
				mReadBuffer.readSignedInt();
				// mReadBuffer.getPositionAndSkip();// tags.add(new Tag(Tag.TAG_KEY_ELE,
				// Integer.toString(mReadBuffer.readSignedInt())));
			}

			mapDatabaseCallback.renderPointOfInterest(layer, latitude, longitude, tags);

		}

		return true;
	}

	private int[] processWayDataBlock(boolean doubleDeltaEncoding) {
		// get and check the number of way coordinate blocks (VBE-U)
		int numBlocks = mReadBuffer.readUnsignedInt();
		if (numBlocks < 1 || numBlocks > Short.MAX_VALUE) {
			LOG.warning("invalid number of way coordinate blocks: " + numBlocks);
			return null;
		}

		int[] wayLengths = new int[numBlocks];

		mWayNodePosition = 0;

		// read the way coordinate blocks
		for (int coordinateBlock = 0; coordinateBlock < numBlocks; ++coordinateBlock) {
			// get and check the number of way nodes (VBE-U)
			int numWayNodes = mReadBuffer.readUnsignedInt();

			if (numWayNodes < 2 || numWayNodes > MAXIMUM_WAY_NODES_SEQUENCE_LENGTH) {
				LOG.warning("invalid number of way nodes: " + numWayNodes);
				logDebugSignatures();
				return null;
			}

			// each way node consists of latitude and longitude
			int len = numWayNodes * 2;

			if (doubleDeltaEncoding) {
				len = decodeWayNodesDoubleDelta(len);
			} else {
				len = decodeWayNodesSingleDelta(len);
			}
			wayLengths[coordinateBlock] = len;
		}

		return wayLengths;
	}

	private int decodeWayNodesDoubleDelta(int length) {
		int[] buffer = mIntBuffer;
		float[] outBuffer = mWayNodes;

		mReadBuffer.readSignedInt(buffer, length);

		int floatPos = mWayNodePosition;

		// get the first way node latitude offset (VBE-S)
		int wayNodeLatitude = mTileLatitude + buffer[0];

		// get the first way node longitude offset (VBE-S)
		int wayNodeLongitude = mTileLongitude + buffer[1];

		// store the first way node
		outBuffer[floatPos++] = wayNodeLongitude;
		outBuffer[floatPos++] = wayNodeLatitude;

		int singleDeltaLatitude = 0;
		int singleDeltaLongitude = 0;

		int cnt = 2, nLon, nLat, dLat, dLon;

		for (int pos = 2; pos < length; pos += 2) {

			singleDeltaLatitude = buffer[pos] + singleDeltaLatitude;
			nLat = wayNodeLatitude + singleDeltaLatitude;
			dLat = nLat - wayNodeLatitude;
			wayNodeLatitude = nLat;

			singleDeltaLongitude = buffer[pos + 1] + singleDeltaLongitude;
			nLon = wayNodeLongitude + singleDeltaLongitude;
			dLon = nLon - wayNodeLongitude;
			wayNodeLongitude = nLon;

			if (dLon > minLon || dLon < -minLon || dLat > minLat || dLat < -minLat || (pos == length - 2)) {
				outBuffer[floatPos++] = nLon;
				outBuffer[floatPos++] = nLat;
				cnt += 2;
			}
		}

		mWayNodePosition = floatPos;

		return cnt;
	}

	private int decodeWayNodesSingleDelta(int length) {
		int[] buffer = mIntBuffer;
		float[] outBuffer = mWayNodes;
		mReadBuffer.readSignedInt(buffer, length);

		int floatPos = mWayNodePosition;

		// get the first way node latitude single-delta offset (VBE-S)
		int wayNodeLatitude = mTileLatitude + buffer[0];

		// get the first way node longitude single-delta offset (VBE-S)
		int wayNodeLongitude = mTileLongitude + buffer[1];

		// store the first way node
		outBuffer[floatPos++] = wayNodeLongitude;
		outBuffer[floatPos++] = wayNodeLatitude;

		int cnt = 2, nLon, nLat, dLat, dLon;

		for (int pos = 2; pos < length; pos += 2) {

			nLat = wayNodeLatitude + buffer[pos];
			dLat = nLat - wayNodeLatitude;
			wayNodeLatitude = nLat;

			nLon = wayNodeLongitude + buffer[pos + 1];
			dLon = nLon - wayNodeLongitude;
			wayNodeLongitude = nLon;

			if (dLon > minLon || dLon < -minLon || dLat > minLat || dLat < -minLat || (pos == length - 2)) {
				outBuffer[floatPos++] = nLon;
				outBuffer[floatPos++] = nLat;
				cnt += 2;
			}
		}

		mWayNodePosition = floatPos;
		return cnt;
	}

	private int stringOffset = -1;

	/*
	 * (non-Javadoc)
	 * @see org.mapsforge.map.reader.IMapDatabase#readString(int)
	 */
	@Override
	public String readString(int position) {
		return mReadBuffer.readUTF8EncodedStringAt(stringOffset + position);
	}

	/**
	 * Processes the given number of ways.
	 * 
	 * @param queryParameters
	 *            the parameters of the current query.
	 * @param mapDatabaseCallback
	 *            the callback which handles the extracted ways.
	 * @param numberOfWays
	 *            how many ways should be processed.
	 * @return true if the ways could be processed successfully, false otherwise.
	 */
	private boolean processWays(QueryParameters queryParameters, IMapDatabaseCallback mapDatabaseCallback,
			int numberOfWays) {

		Tag[] tags = null;
		Tag[] wayTags = mMapFileHeader.getMapFileInfo().wayTags;
		int[] textPos = new int[3];
		// float[] labelPosition;
		boolean skippedWays = false;
		int wayDataBlocks;

		// skip string block
		int stringsSize = mReadBuffer.readUnsignedInt();
		stringOffset = mReadBuffer.getBufferPosition();
		mReadBuffer.skipBytes(stringsSize);

		for (int elementCounter = numberOfWays; elementCounter != 0; --elementCounter) {
			if (mDebugFile) {
				// get and check the way signature
				mSignatureWay = mReadBuffer.readUTF8EncodedString(SIGNATURE_LENGTH_WAY);
				if (!mSignatureWay.startsWith("---WayStart")) {
					LOG.warning("invalid way signature: " + mSignatureWay);
					LOG.warning(DEBUG_SIGNATURE_BLOCK + mSignatureBlock);
					return false;
				}
			}

			if (queryParameters.useTileBitmask) {
				elementCounter = mReadBuffer.skipWays(queryParameters.queryTileBitmask, elementCounter);

				if (elementCounter == 0)
					return true;

				if (elementCounter < 0)
					return false;

				if (mReadBuffer.lastTagPosition > 0) {
					int pos = mReadBuffer.getBufferPosition();
					mReadBuffer.setBufferPosition(mReadBuffer.lastTagPosition);

					byte numberOfTags = (byte) (mReadBuffer.readByte() & WAY_NUMBER_OF_TAGS_BITMASK);

					tags = mReadBuffer.readTags(wayTags, numberOfTags);
					if (tags == null)
						return false;

					skippedWays = true;

					mReadBuffer.setBufferPosition(pos);
				}
			} else {
				int wayDataSize = mReadBuffer.readUnsignedInt();
				if (wayDataSize < 0) {
					LOG.warning("invalid way data size: " + wayDataSize);
					if (mDebugFile) {
						LOG.warning(DEBUG_SIGNATURE_BLOCK + mSignatureBlock);
					}
					LOG.warning("EEEEEK way... 2");
					return false;
				}

				// ignore the way tile bitmask (2 bytes)
				mReadBuffer.skipBytes(2);
			}

			// get the special byte which encodes multiple flags
			byte specialByte = mReadBuffer.readByte();

			// bit 1-4 represent the layer
			byte layer = (byte) ((specialByte & WAY_LAYER_BITMASK) >>> WAY_LAYER_SHIFT);
			// bit 5-8 represent the number of tag IDs
			byte numberOfTags = (byte) (specialByte & WAY_NUMBER_OF_TAGS_BITMASK);

			boolean changed = skippedWays;
			skippedWays = false;

			if (numberOfTags != 0) {
				tags = mReadBuffer.readTags(wayTags, numberOfTags);
				changed = true;
			}
			if (tags == null)
				return false;

			// get the feature bitmask (1 byte)
			byte featureByte = mReadBuffer.readByte();

			// bit 1-6 enable optional features
			boolean featureWayDoubleDeltaEncoding = (featureByte & WAY_FEATURE_DOUBLE_DELTA_ENCODING) != 0;

			// check if the way has a name
			if ((featureByte & WAY_FEATURE_NAME) != 0)
				textPos[0] = mReadBuffer.readUnsignedInt();
			else
				textPos[0] = -1;

			// check if the way has a house number
			if ((featureByte & WAY_FEATURE_HOUSE_NUMBER) != 0)
				textPos[1] = mReadBuffer.readUnsignedInt();
			else
				textPos[1] = -1;

			// check if the way has a reference
			if ((featureByte & WAY_FEATURE_REF) != 0)
				textPos[2] = mReadBuffer.readUnsignedInt();
			else
				textPos[2] = -1;

			if ((featureByte & WAY_FEATURE_LABEL_POSITION) != 0)
				// labelPosition =
				readOptionalLabelPosition();
			// else
			// labelPosition = null;

			if ((featureByte & WAY_FEATURE_DATA_BLOCKS_BYTE) != 0) {
				wayDataBlocks = mReadBuffer.readUnsignedInt();

				if (wayDataBlocks < 1) {
					LOG.warning("invalid number of way data blocks: " + wayDataBlocks);
					logDebugSignatures();
					return false;
				}
			} else {
				wayDataBlocks = 1;
			}

			for (int wayDataBlock = 0; wayDataBlock < wayDataBlocks; ++wayDataBlock) {
				int[] wayLengths = processWayDataBlock(featureWayDoubleDeltaEncoding);
				if (wayLengths == null)
					return false;

				// wayDataContainer.textPos = textPos;
				mapDatabaseCallback.renderWay(layer, tags, mWayNodes, wayLengths, changed);
			}
		}

		return true;
	}

	private float[] readOptionalLabelPosition() {
		float[] labelPosition = new float[2];

		// get the label position latitude offset (VBE-S)
		labelPosition[1] = mTileLatitude + mReadBuffer.readSignedInt();

		// get the label position longitude offset (VBE-S)
		labelPosition[0] = mTileLongitude + mReadBuffer.readSignedInt();

		return labelPosition;
	}

	// private int readOptionalWayDataBlocksByte(boolean featureWayDataBlocksByte) {
	// if (featureWayDataBlocksByte) {
	// // get and check the number of way data blocks (VBE-U)
	// return mReadBuffer.readUnsignedInt();
	// }
	// // only one way data block exists
	// return 1;
	// }

	private int[][] readZoomTable(SubFileParameter subFileParameter) {
		int rows = subFileParameter.zoomLevelMax - subFileParameter.zoomLevelMin + 1;
		int[][] zoomTable = new int[rows][2];

		int cumulatedNumberOfPois = 0;
		int cumulatedNumberOfWays = 0;

		for (int row = 0; row < rows; ++row) {
			cumulatedNumberOfPois += mReadBuffer.readUnsignedInt();
			cumulatedNumberOfWays += mReadBuffer.readUnsignedInt();

			if (cumulatedNumberOfPois < 0 || cumulatedNumberOfPois > MAXIMUM_ZOOM_TABLE_OBJECTS) {
				LOG.warning("invalid cumulated number of POIs in row " + row + ' ' + cumulatedNumberOfPois);
				if (mDebugFile) {
					LOG.warning(DEBUG_SIGNATURE_BLOCK + mSignatureBlock);
				}
				return null;
			} else if (cumulatedNumberOfWays < 0 || cumulatedNumberOfWays > MAXIMUM_ZOOM_TABLE_OBJECTS) {
				LOG.warning("invalid cumulated number of ways in row " + row + ' ' + cumulatedNumberOfWays);
				if (mMapFileHeader.getMapFileInfo().debugFile) {
					LOG.warning(DEBUG_SIGNATURE_BLOCK + mSignatureBlock);
				}
				return null;
			}

			zoomTable[row][0] = cumulatedNumberOfPois;
			zoomTable[row][1] = cumulatedNumberOfWays;
		}

		return zoomTable;
	}
}
