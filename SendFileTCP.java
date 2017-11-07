import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;



public class SendFileTCP {
	static int data_size = 1012;     
    static int timeoutVal = 500;	
	String fileName;
    Semaphore sema;
	String pathToFile;				// path of file to be sent
	int base;					    // start number of a window
	int nextSeqNum;				    // next in sequence inside window
				
	Timer time_out;				// for timeouts	
	static int window_size = 10;
			
	
	boolean transComp;	// if client has completely received the file
	
	public void setTimer(boolean isNewTimer){
		// function to start and stop timer
		if (time_out != null) 
			time_out.cancel();
		if (isNewTimer){
			time_out = new Timer();
			time_out.schedule(new Timeout(), timeoutVal);
		}
	}
	
	// function to perform when timeout
	public class Timeout extends TimerTask{
		public void run(){
			try{
				sema.acquire();	
				System.out.println(">> Timeout!");
				nextSeqNum = base;	
				sema.release();	
			} catch(InterruptedException e){
				e.printStackTrace();
			}
		}
	}
	
	Vector<byte[]> packetsList;	 
	
	// class for reciving acknowledgement number
	public class IncommingDataClass extends Thread {
		private DatagramSocket sk_in;

		// constructor
		public IncommingDataClass(DatagramSocket sk_in) {
			this.sk_in = sk_in;
		}

		// returns -1 if corrupted, else return Ack number
		int decPack(byte[] pkt){
			byte[] received_checksumBytes = copyOfRange(pkt, 0, 8);
			byte[] ackNumBytes = copyOfRange(pkt, 8, 12);
			CRC32 checksum = new CRC32();
			checksum.update(ackNumBytes);
			byte[] calculated_checksumBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();// checksum (8 bytes)
			if (Arrays.equals(received_checksumBytes, calculated_checksumBytes)) return ByteBuffer.wrap(ackNumBytes).getInt();
			else return -1;
		}
		
		// receiving process (updates base)
		public void run() {
			try {
				byte[] in_data = new byte[12];	// ack packet with no data
				DatagramPacket in_pkt = new DatagramPacket(in_data,	in_data.length);
				try {
					// while there are still packets yet to be received by receiver
					while (!transComp) {
						
						sk_in.receive(in_pkt);
						int ackNum = decPack(in_data);
						System.out.println(">> Received ACK " + ackNum);
						
						// if ack is not corrupted
						if (ackNum != -1){
							// if duplicate ack
							if (base == ackNum + 1){
								sema.acquire();	
								setTimer(false);	
								nextSeqNum = base;		
								sema.release();	
							}
							// else if teardown ack
							else if (ackNum == -2) 
								transComp = true;
							// else normal ack
							else{
								base = ackNum++;	
								sema.acquire();
								if (base == nextSeqNum) setTimer(false);	// sent sucessfully 
								else setTimer(true);						// start again
								sema.release();	 
							}
						}
						// else if ack corrupted, do nothing
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					sk_in.close();
					System.out.println(">> incomming socket closed!");
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}
	}

	public byte[] copyOfRange(byte[] srcArr, int start, int end){
		int length = (end > srcArr.length)? srcArr.length-start: end-start;
		byte[] destArr = new byte[length];
		System.arraycopy(srcArr, start, destArr, 0, length);
		return destArr;
	}
	
	// Class for sending data
	public class SendDataClass extends Thread {
		private DatagramSocket sk_out;
		private int dst_port;
		private InetAddress dst_addr;
		private int recv_port;

		public SendDataClass(DatagramSocket sk_out, int dst_port, int recv_port) {
			this.sk_out = sk_out;
			this.dst_port = dst_port;
			this.recv_port = recv_port;
		}
		
		// this threadd will send information 
		public void run(){
			try{
				 
				 FileInputStream fis = new FileInputStream(new File(pathToFile));
				 dst_addr = InetAddress.getByName("127.0.0.1"); 
				
				
				 
				try {
					// while there are still packets yet to be received by client
					while (!transComp){
						// send packets if window is not yet full
						if (nextSeqNum < base + window_size){
							
							sema.acquire();	
							if (base == nextSeqNum) 
								setTimer(true);	// timer needs to start at the start of packet
							
							byte[] out_data = new byte[10];
							boolean isFinalSeqNum = false;
							
							// already in list
							if (nextSeqNum < packetsList.size()){
								out_data = packetsList.get(nextSeqNum);
							}
							
							else{
								// if first packet, special handling: prepend file information
								if (nextSeqNum == 0){
									byte[] fileNameBytes = fileName.getBytes();
									byte[] fileNameLengthBytes = ByteBuffer.allocate(4).putInt(fileNameBytes.length).array();
									byte[] dataBuffer = new byte[data_size];
									int dataLength = fis.read(dataBuffer, 0, data_size - 4 - fileNameBytes.length);
									byte[] dataBytes = copyOfRange(dataBuffer, 0, dataLength);
									ByteBuffer finalBuff = ByteBuffer.allocate(4 + fileNameBytes.length + dataBytes.length);
									finalBuff.put(fileNameLengthBytes);	
									finalBuff.put(fileNameBytes);			
									finalBuff.put(dataBytes);				
									
									byte[] seqNumBytes = ByteBuffer.allocate(4).putInt(nextSeqNum).array(); 	
			

			                        CRC32 checksum = new CRC32(); // first checksum
			                        checksum.update(seqNumBytes);
			                        checksum.update(finalBuff.array());
			                        byte[] checksumBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();	// checksum (8 bytes)
			

			                        ByteBuffer packetBuffer = ByteBuffer.allocate(12 + finalBuff.array().length);
			                        packetBuffer.put(checksumBytes);
			                        packetBuffer.put(seqNumBytes);
			                        packetBuffer.put(finalBuff.array());
			                        out_data = packetBuffer.array();
								}
								// else if subsequent packets
								else{
									byte[] dataBuffer = new byte[data_size];
									int dataLength = fis.read(dataBuffer, 0, data_size);
									// if no more data to be read, send empty data. i.e. finalSeqNum
									if (dataLength == -1){
										isFinalSeqNum = true;
										
										byte[] Empty_bytes = new byte[0];
										byte[] seqNumBytes = ByteBuffer.allocate(4).putInt(nextSeqNum).array(); 	
			
		 
			                        CRC32 checksum = new CRC32();
			                        checksum.update(seqNumBytes);
			                        checksum.update(Empty_bytes);
			                        byte[] checksumBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();	// checksum (8 bytes)
			
			
			                        ByteBuffer packetBuffer = ByteBuffer.allocate(12 + Empty_bytes.length);
			                        packetBuffer.put(checksumBytes);
			                        packetBuffer.put(seqNumBytes);
			                        packetBuffer.put(Empty_bytes);
			                        out_data = packetBuffer.array();
									}
									// else if valid data
									else{
										byte[] dataBytes = copyOfRange(dataBuffer, 0, dataLength);
										
										byte[] seqNumBytes = ByteBuffer.allocate(4).putInt(nextSeqNum).array();
										
										CRC32 checksum = new CRC32();
			                            checksum.update(seqNumBytes);
			                            checksum.update(dataBytes);
			                            byte[] checksumBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();	// checksum (8 bytes)
			
		
			                            ByteBuffer packetBuffer = ByteBuffer.allocate(12 + dataBytes.length);
			                            packetBuffer.put(checksumBytes);
			                            packetBuffer.put(seqNumBytes);
			                            packetBuffer.put(dataBytes);
			                            out_data = packetBuffer.array();
									}
								}
								packetsList.add(out_data);	// add to packetsList
							}
							
							// send the packet
							sk_out.send(new DatagramPacket(out_data, out_data.length, dst_addr, dst_port));
							System.out.println(">> Sent sequence Number " + nextSeqNum);
							
							// update nextSeqNum if currently not at FinalSeqNum
							if (!isFinalSeqNum) nextSeqNum++;
							sema.release();	
						}
						sleep(5);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
				setTimer(false);	// close timer
				sk_out.close();		// close outgoing socket
				fis.close();		// close FileInputStream
				System.out.println(">> out socket closed!");
				
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}
	}

	
	// sender constructor
	public SendFileTCP(int portToSend, int portToRecive, String path, String fileName) {
		packetsList = new Vector<byte[]>(window_size);
		transComp = false;
		DatagramSocket sendSock, recSock;
		sema = new Semaphore(1);
		base = 0;
		nextSeqNum = 0;
		this.pathToFile = path;
		this.fileName = fileName;
		
		System.out.println("port to send=" + portToSend + ", port to recive from=" + portToRecive + ", input File Path=" + path + ", outputFileName=" + fileName);
		
		try {
			// socket creations
			sendSock = new DatagramSocket();			   // Sending Socket 	
			recSock = new DatagramSocket(portToRecive);	   //reciving socket on different port number 

			// create threads to process data
			SendDataClass send_out = new SendDataClass(sendSock, portToSend, portToRecive);
			IncommingDataClass rec = new IncommingDataClass(recSock);
			
			
			// start thread and sending
			send_out.start();
			rec.start();
			
			
		} catch (Exception e) {
			e.printStackTrace();
		
		}
	}

	
	
	
	public static void main(String[] args) {
		// parse parameters
		if (args.length != 4) {
			System.err.println("Argument Missing");
			System.err.println("run like following: java Sender port_to_send port_to_recive inputFilePath outputFileName");
			
		}
		else new SendFileTCP(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2], args[3]);
	}
}
