package code;

import java.io.File;
import java.util.Collections;
import java.util.Vector;

public class RecordManager  {

	private static BufferManager bufferManager = new BufferManager(); //buffer manager

	//create a file for new table, return true if success, otherwise return false
	public static boolean create_table(String tableName) {
		try {
			File file =new File(tableName);
			if(!file.createNewFile()) //file alrebady exists
				return false;
			Block block = bufferManager.read_block_from_disk_quote(tableName,0); //read first block from file
			block.write_integer(0, -1); //write to free list head, -1 means no free space
			return true;
		} catch(Exception e) {
			System.out.println(e.getMessage());
			return false;
		}
	}

	//delete the file of given table, return true if success, otherwise return false
	public static boolean drop_table(String tableName) {
		File file =new File(tableName);
		if(file.delete()) { //delete the file
			bufferManager.make_invalid(tableName); // set the block invalid
			return true;
		} else {
			return false;
		}
	}

	//select tuples from given table according to conditions, return result tuples
	public static Vector<TableRow> select(String tableName, Vector<Condition> conditions) {
		int tupleNum = CatalogManager.get_row_num(tableName);
		int storeLen = get_store_length(tableName);

		int processNum = 0; //number of processed tuples
		int byteOffset = FieldType.INTSIZE; //byte offset in block, skip file header
		int blockOffset = 0; //block offset in file
		Block block = bufferManager.read_block_from_disk_quote(tableName,0); //get first block

		Vector<TableRow> result = new Vector<>(); //table row result

		while(processNum < tupleNum) { //scan the block in sequence
			if (byteOffset + storeLen >= Block.BLOCKSIZE) { //find next block
				blockOffset++;
				block = bufferManager.read_block_from_disk_quote(tableName, blockOffset); //read next block
				byteOffset = 0; //reset byte offset
			}
			if(block.read_integer(byteOffset) < 0) { //tuple is valid
				int i;
				TableRow newRow = get_tuple(tableName,block,byteOffset);
				for(i = 0;i < conditions.size();i++) { //check all conditions
					if(!conditions.get(i).satisfy(tableName,newRow))
						break;
				}
				if(i == conditions.size()) { //if satisfy all conditions
					result.add(newRow); //add new row to result
				}
				processNum++; //update processed tuple number
			}
			byteOffset += storeLen; //update byte offset
		}
		return result;
	}

	//insert the tuple in given table, return the inserted address
	public static Address insert(String tableName, TableRow data) {
		int tupleNum = CatalogManager.get_row_num(tableName);

		Block headBlock = bufferManager.read_block_from_disk_quote(tableName,0); //get first block
		headBlock.lock(true); //lock first block for later write

		int freeOffset = headBlock.read_integer(0); //read the first free offset in file header
		int tupleOffset;

		if(freeOffset < 0) { //no free space
			tupleOffset = tupleNum; //add to tail of the file
		} else {
			tupleOffset = freeOffset; //add to free offset
		}

		int blockOffset = get_block_offset(tableName, tupleOffset); //block offset of tuple
		int byteOffset = get_byte_offset(tableName, tupleOffset); //byte offset of tuple
		Block insertBlock = bufferManager.read_block_from_disk_quote(tableName,blockOffset); //read the block for inserting

		if(freeOffset >= 0) { //if head has free offset, update it
			freeOffset = insertBlock.read_integer(byteOffset + 1); //get next free address
			headBlock.write_integer(0,freeOffset); //write new free offset to head
		}

		headBlock.lock(false); //unlock head block
		write_tuple(tableName,data,insertBlock,byteOffset); //write data to insert block
		return new Address(tableName,blockOffset,byteOffset); //return insert address*/
	}

	//delete the condition-satisfied tuples from given table, return number of deleted tuples
	public static int delete(String tableName, Vector<Condition> conditions) {
		int tupleNum = CatalogManager.get_row_num(tableName);
		int storeLen = get_store_length(tableName);

		int processNum = 0; //number of processed tuples
		int byteOffset = FieldType.INTSIZE; //byte offset in block, skip file header
		int blockOffset = 0; //block offset in file
		int deleteNum = 0; // number of delete tuples

		Block headBlock = bufferManager.read_block_from_disk_quote(tableName,0); //get first block
		Block laterBlock = headBlock; //block for sequently scanning
		headBlock.lock(true); //lock head block for free list update

		for(int currentNum = 0;processNum < tupleNum; currentNum++) { //scan the block in sequence
			if (byteOffset + storeLen >= Block.BLOCKSIZE) { //byte overflow, find next block
				blockOffset++;
				laterBlock = bufferManager.read_block_from_disk_quote(tableName, blockOffset); //read next block
				byteOffset = 0; //reset byte offset
			}
			if(laterBlock.read_integer(byteOffset) < 0) { //tuple is valid
				int i;
				TableRow newRow = get_tuple(tableName,laterBlock,byteOffset); //get current tuple
				for(i = 0;i < conditions.size();i++) { //check all conditions
					if(!conditions.get(i).satisfy(tableName,newRow))
						break;
				}
				if(i == conditions.size()) { //if satisfy all conditions, delete the tuple
					laterBlock.write_integer(byteOffset,0); //set vaild byte to 0
					laterBlock.write_integer(byteOffset + 1, headBlock.read_integer(0)); //set free offset
					headBlock.write_integer(0,currentNum); //write deleted offset to head pointer
					deleteNum++;
				}
				processNum++; //update processed tuple number
			}
			byteOffset += storeLen; //update byte offset
		}
		return deleteNum;
	}

	//select the tuple from given list of address on one table, return result list of tuples
	public static Vector<TableRow> select(Vector<Address> address) {
		if(address.size() == 0) //empty address
			return new Vector<>();

		Collections.sort(address); //sort address

		String tableName = address.get(0).get_file_name(); //get table name
		int blockOffset = 0, blockOffsetPre = -1; //current and previous block offset
		int byteOffset = 0; //current byte offset

		Block block = null;
		Vector<TableRow> result = new Vector<>();

		for(int i = 0;i < address.size(); i++) { //for each later address
			blockOffset = address.get(i).get_block_offset(); //read block and byte offset
			byteOffset = address.get(i).get_byte_offset();
			if(i == 0 || blockOffset != blockOffsetPre) { // not in same block as previous
				block = bufferManager.read_block_from_disk_quote(tableName,blockOffset); // read a new block
			}
			result.add(get_tuple(tableName,block,byteOffset)); //add tuple
			blockOffsetPre = blockOffset;
		}
		return result;
	}

	//delete the tuples from given address on one table, return the number of delete tuples
	public static int delete(Vector<Address> address) {
		if(address.size() == 0) //empty address
			return 0;

		Collections.sort(address); //sort address

		String tableName = address.get(0).get_file_name(); //get table name

		int blockOffset = 0,blockOffsetPre = -1; //current and previous block offset
		int byteOffset = 0; //current byte offset
		int tupleOffset = 0; //tuple offset in file

		Block headBlock = bufferManager.read_block_from_disk_quote(tableName,0); //get head block
		Block deleteBlock = null;

		headBlock.lock(true); //lock head block for free list update

		int deleteNum = 0; // number of delete tuple
		for(int i = 0;i < address.size();i++) { //for each address
			blockOffset = address.get(i).get_block_offset(); //read block and byte offset
			byteOffset = address.get(i).get_byte_offset();
			tupleOffset = get_tuple_offset(tableName, blockOffset, byteOffset);

			if(i == 0 || blockOffset != blockOffsetPre) { // not in same block
				deleteBlock = bufferManager.read_block_from_disk_quote(tableName,blockOffset); // read a new block
			}

			if (deleteBlock.read_integer(byteOffset) < 0) { //tuple is valid
				deleteBlock.write_integer(byteOffset, 0); //set valid byte to 0
				deleteBlock.write_integer(byteOffset + 1, headBlock.read_integer(0)); //set free address
				headBlock.write_integer(0, tupleOffset); //write delete offset to head
				deleteNum++;
			}
		}

		headBlock.lock(false); //unlock head block
		return deleteNum;
	}

	//do projection on given result and projected attribute name in given table, return the projection result
	public static Vector<TableRow> project(String tableName, Vector<TableRow> result, Vector<String> projectName) {
		int attributeNum = CatalogManager.get_attribute_num(tableName);
		Vector<TableRow> projectResult = new Vector<>();
		for(int i = 0;i < result.size();i++) { //for each tuple in result
			TableRow newRow = new TableRow();
			for(int j = 0;j < projectName.size();j++) { //for each project attribute name
				int index = CatalogManager.get_attribute_index(tableName, projectName.get(j)); //get index
				newRow.add_attribute_value(result.get(i).get_attribute_value(index)); //set attribute to tuple
			}
			projectResult.add(newRow);
		}

		return projectResult;
	}

	//store the record from buffer to file
	public static void store_record() {
		bufferManager.destruct_buffer_manager();
	}

	//get the length for one tuple to store in given table
	private static int get_store_length(String tableName) {
		int rowLen = CatalogManager.get_row_length(tableName); //actual length
		if(rowLen > FieldType.INTSIZE) { //add a valid byte in head
			return rowLen + FieldType.CHARSIZE;
		} else { //empty address pointer + valid byte
			return FieldType.INTSIZE + FieldType.CHARSIZE;
		}
	}

	//get the block offset of given table and tuple offset
	private static int get_block_offset(String tableName, int tupleOffset) {
		int storeLen = get_store_length(tableName);
		int tupleInFirst = (Block.BLOCKSIZE - FieldType.INTSIZE) / storeLen; //number of tuples in first block
		int tupleInNext = Block.BLOCKSIZE / storeLen; //number of tuples in later block

		if(tupleOffset < tupleInFirst) { //in first block
			return 0;
		} else { //in later block
			return (tupleOffset - tupleInFirst) / tupleInNext + 1;
		}
	}

	//get the byte offset of given table and tuple offset
	private static int get_byte_offset(String tableName, int tupleOffset) {
		int storeLen = get_store_length(tableName);
		int tupleInFirst = (Block.BLOCKSIZE - FieldType.INTSIZE) / storeLen; //number of tuples in first block
		int tupleInNext = Block.BLOCKSIZE / storeLen; //number of tuples in later block

		int blockOffset = get_block_offset(tableName, tupleOffset);
		if(blockOffset == 0) { //in first block
			return tupleOffset * storeLen + FieldType.INTSIZE;
		} else { //in later block
			return (tupleOffset - tupleInFirst - (blockOffset - 1) * tupleInNext) * storeLen;
		}
	}

	//get the tuple offset of given table, block offset and byte offset
	private static int get_tuple_offset(String tableName, int blockOffset, int byteOffset) {
		int storeLen = get_store_length(tableName);
		int tupleInFirst = (Block.BLOCKSIZE - FieldType.INTSIZE) / storeLen; //number of tuples in first block
		int tupleInNext = Block.BLOCKSIZE / storeLen; //number of tuples in later block

		if(blockOffset == 0) { //in first block
			return (byteOffset - FieldType.INTSIZE) / storeLen;
		} else { //in later block
			return tupleInFirst + (blockOffset - 1) * tupleInNext + byteOffset / storeLen;
		}
	}

	//get the tuple from given table according to stored block and start byte offset
	private static TableRow get_tuple(String tableName, Block block, int offset) {
		int attributeNum = CatalogManager.get_attribute_num(tableName); //number of attribute
		String attributeValue = null;
		TableRow result = new TableRow();

		offset++; //skip first valid flag

		for (int i = 0; i < attributeNum; i++) { //for each attribute
			int length = CatalogManager.get_length(tableName, i); //get length
			String type = CatalogManager.get_type(tableName, i); //get type
			if (type.equals("char")) { //char type
				attributeValue = block.read_string(offset, length);
			} else if (type.equals("int")) { //integer type
				attributeValue = String.valueOf(block.read_integer(offset));
			} else if (type.equals("float")) { //float type
				attributeValue = String.valueOf(block.read_float(offset));
			}
			offset += length;
			result.add_attribute_value(attributeValue); //add attribute to row
		}
		return result;
	}

	//write a tuple to given table according to stored block and start byte offset
	private static void write_tuple(String tableName, TableRow data, Block block, int offset) {
		int attributeNum = CatalogManager.get_attribute_num(tableName); //number of attribute

		block.write_integer(offset,-1); //set valid byte to 11111111
		offset++; //skip first valid flag

		for (int i = 0; i < attributeNum; i++) { //for each attribute
			int length = CatalogManager.get_length(tableName, i); //get length
			String type = CatalogManager.get_type(tableName, i); //get type
			if (type.equals("char")) { //char type
				block.write_string(offset,data.get_attribute_value(i));
			} else if (type.equals("int")) { //integer type
				block.write_integer(offset, Integer.parseInt(data.get_attribute_value(i)));
			} else if (type.equals("float")) { //float type
				block.write_float(offset, Float.parseFloat(data.get_attribute_value(i)));
			}
			offset += length;
		}
	}

}