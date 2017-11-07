import java.io.File;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;

public class Client {
	
	static int packet_size = 1000;
	
	public Client(int socket2_destination_port, int socket3_destination_port, String path) {
		DatagramSocket socket2, socket3;
		
		int prevSequenceNumber = -1;				
		int nextSequenceNumber = 0;					
		boolean Status = false;	
		
		try {
			socket2 = new DatagramSocket(socket2_destination_port);	
			socket3 = new DatagramSocket();				
			try {
				byte[] incoming_data = new byte[packet_size];									
				
				DatagramPacket in_pkt = new DatagramPacket(incoming_data,	incoming_data.length);	
				InetAddress destination_addr = InetAddress.getByName("127.0.0.1");
				
				FileOutputStream os = null;
				
				path = ((path.substring(path.length()-1)).equals("/"))? path: path + "/";	
				File filePath = new File(path);
				if (!filePath.exists()) filePath.mkdir();
				
				
				while (!Status) {
					
					socket2.receive(in_pkt);

					byte[] received_checksum = cpyRange(incoming_data, 0, 8);
					CRC32 checksum = new CRC32();
					checksum.update(cpyRange(incoming_data, 8, in_pkt.getLength()));
					byte[] calculated_checksum = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();
					
					
					if (Arrays.equals(received_checksum, calculated_checksum)){
						int SequenceNumber = ByteBuffer.wrap(cpyRange(incoming_data, 8, 12)).getInt();
						System.out.println("Client: Received sequence number: " + SequenceNumber);
						
						
						if (SequenceNumber == nextSequenceNumber){
							
							if (in_pkt.getLength() == 12){
								byte[] ackPacket = genPackt(-2);	
								for (int i=0; i<20; i++) socket3.send(new DatagramPacket(ackPacket, ackPacket.length, destination_addr, socket3_destination_port));
								Status = true;			
								System.out.println("Client: All packets received! File Created!");
								continue;	
							}
							
							else{
								byte[] ackPacket = genPackt(SequenceNumber);
								socket3.send(new DatagramPacket(ackPacket, ackPacket.length, destination_addr, socket3_destination_port));
								System.out.println("Client: Sent Ack " + SequenceNumber);
							}
							
							
							if (SequenceNumber==0 && prevSequenceNumber==-1){
								int fileNameLength = ByteBuffer.wrap(cpyRange(incoming_data, 12, 16)).getInt();	// 0-8:checksum, 8-12:SequenceNumber
								String fileName = new String(cpyRange(incoming_data, 16, 16 + fileNameLength));	// decode file name
								System.out.println("Client: fileName length: " + fileNameLength + ", fileName:" + fileName);
								
								
								File file = new File(path + fileName);
								if (!file.exists()) file.createNewFile();
								
								
								os = new FileOutputStream(file);
								
								
								os.write(incoming_data, 16 + fileNameLength, in_pkt.getLength() - 16 - fileNameLength);
							}
							
							
							else os.write(incoming_data, 12, in_pkt.getLength() - 12);
							
							nextSequenceNumber ++; 			
							prevSequenceNumber = SequenceNumber;	
						}
						
						
						else{
							byte[] ackPacket = genPackt(prevSequenceNumber);
							socket3.send(new DatagramPacket(ackPacket, ackPacket.length, destination_addr, socket3_destination_port));
							System.out.println("Client: Sent duplicate Ack " + prevSequenceNumber);
						}
					}
					
					
					else{
						System.out.println("Client: Corrupt packet dropped");
						byte[] ackPacket = genPackt(prevSequenceNumber);
						socket3.send(new DatagramPacket(ackPacket, ackPacket.length, destination_addr, socket3_destination_port));
						System.out.println("Client: Sent duplicate Ack " + prevSequenceNumber);
					}
				}
				if (os != null) os.close();
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			} finally {
				socket2.close();
				socket3.close();
				System.out.println("Client: socket2 closed!");
				System.out.println("Client: socket3 closed!");
			}
		} catch (SocketException e1) {
			e1.printStackTrace();
		}
	}
	public static void main(String[] args) {
		if (args.length != 3) {
			System.err.println("Usage: Client socket2_destination_port, socket3_destination_port, outputFolderPath");
			System.exit(-1);
		}
		else new Client(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2]);
	}
	public byte[] cpyRange(byte[] srcArr, int start, int end){
		int length = (end > srcArr.length)? srcArr.length-start: end-start;
		byte[] destArr = new byte[length];
		System.arraycopy(srcArr, start, destArr, 0, length);
		return destArr;
	}
	
	
	public byte[] genPackt(int ackNum){
		byte[] ackNumBytes = ByteBuffer.allocate(4).putInt(ackNum).array();
		CRC32 checksum = new CRC32();
		checksum.update(ackNumBytes);
		ByteBuffer pktBuf = ByteBuffer.allocate(12);
		pktBuf.put(ByteBuffer.allocate(8).putLong(checksum.getValue()).array());
		pktBuf.put(ackNumBytes);
		return pktBuf.array();
	}

}