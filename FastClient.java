import java.io.BufferedReader;
import java.util.TimerTask;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Timer;
import java.io.IOException;
import java.util.*;


/**
 * FastClient Class
 * 
 * FastClient implements a basic reliable FTP client application based on UDP data transmission and selective repeat protocol
 * 
 */
public class FastClient 
{

	private String serverName;
	private int serverPort;
	private int timeout;
	private int windowSize;
	private InetAddress address;
	public static int numPackets=0; //Checking for correctness
	public static int numResent=0;
	private TxQueue transQueue;
	private boolean programDone;
	private DatagramSocket clientSocket;
	private int lowWindow;
	private int highWindow;
	private TimerTask newTask;
	private Timer timer;
	
 	/**
        * Constructor to initialize the program 
        * 
        * @param server_name    server name or IP
        * @param server_port    server port
        * @param file_name      file to be transfered
        * @param window         window size
	* @param timeout	time out value
        */
	public FastClient(String server_name, int server_port, int window, int timeout) 
	{
		//Initialize values
		this.serverName = server_name;
		this.serverPort = server_port;
		this.timeout = timeout;
		this.windowSize = window;
		this.transQueue = new TxQueue(this.windowSize);
		this.programDone = false;
		this.lowWindow = 0;
		this.highWindow = window-1;
		try
		{
			this.clientSocket = new DatagramSocket(); 
			this.address = InetAddress.getByName(server_name);
		}
		catch(Exception e)
		{
			System.out.print(e);
			System.exit(0);
		}
	}
	
	/* send file */
	public void send(String file_name) 
	{
		//2.Start ACK RCV Thread
		Thread ackThread = new ACKReceiveThread();
		ackThread.start();
		
		try
		{
			String currLocation = System.getProperty("user.dir");
			//FileInputStream fileIn = new FileInputStream(currLocation+"/src/"+file_name); //Windows with eclipse
			//FileInputStream fileIn = new FileInputStream(currLocation+"/"+file_name); //Windows
			FileInputStream fileIn = new FileInputStream(currLocation+"/"+file_name); //Linux
			Segment aSegment = new Segment();
		    byte[] sendData = new byte[aSegment.MAX_PAYLOAD_SIZE]; 
			int bytesRead;
			int sequenceNumber = -1;
			boolean endFlag = false;
			BufferedReader inFromUser = new BufferedReader(new InputStreamReader(System.in)); 
		
			//3.While  not the end of the file 
			while((bytesRead=fileIn.read(sendData))!=-1)
			{
				//4.Create segment
				Segment sendSegment;
				sequenceNumber++; //Increase by 1 for each segment
				numPackets++;
				if(bytesRead<1000)
				{
					byte[] tmp = new byte[bytesRead];
					for(int i=0;i<bytesRead;i++)
					{
						tmp[i] = sendData[i];
					}
					sendSegment = new Segment(sequenceNumber, tmp);
				}
				else
				{
					sendSegment = new Segment(sequenceNumber, sendData);
				}
				while(transQueue.isFull())
				{
					//5.Wait for queue to have open spot before continuing
				}
				//6.call processSend
				processSend(sendSegment,true);
			}//7.End of while
			while(!(transQueue.isEmpty()))
			{
				//8.Wait for transmission queue to be completely empty
			}
			programDone=true; //Terminate ack thread
			ackThread.stop();
			timer.cancel(); //Terminate timer
			timer.purge();
			newTask.cancel();
			clientSocket.close();
		}
		catch (Exception e)
		{
			 //If any exception occurs, display it
			 System.out.println("Error " + e);
		}
	}

	public synchronized void processSend(Segment sendSegment, boolean firstTime)
	{
		try
		{
			Segment bSegment = new Segment();
			byte[] segmentByteArray = new byte[bSegment.MAX_SEGMENT_SIZE]; 
		
			if(firstTime==true)
			{
				transQueue.add(sendSegment);
				transQueue.getNode(sendSegment.getSeqNum()).setStatus(0); //Segment sent, but not yet acknowledged
			}
			segmentByteArray = sendSegment.getBytes();
			DatagramPacket sendPacket = new DatagramPacket(segmentByteArray,segmentByteArray.length,address,this.serverPort);
			clientSocket.send(sendPacket);
			
			//Begin timer, resend if packet is lost
			newTask = new TimeOutHandler(sendSegment,serverName,serverPort,windowSize,timeout);
			timer = new Timer();
			timer.schedule(newTask, this.timeout);
		}
		catch(Exception e)
		{
			System.out.println(e);
			System.exit(0);
		}
	}
			
	public synchronized void processAck(Segment AckSegment)
	{
		int ack = AckSegment.getSeqNum();
		try
		{
			if((ack>=lowWindow)&&(ack<=highWindow))
			{
				transQueue.getNode(ack).setStatus(1); //Segment has been acknowledged
			
				while(transQueue.getHeadNode().getStatus()==1)
				{
					try
					{
						transQueue.remove();
						lowWindow++;
						highWindow++;
					}
					catch(Exception e)
					{
						System.out.println(e);
						System.exit(0);
					}
				}
			}
		}
		catch(NullPointerException e)
		{
			//Do nothing (just means were done, program will be terminated right after)
		}
	}
	
	public synchronized void processTime(int seqNum)
	{
		try
		{
			if((transQueue.getNode(seqNum).getStatus()!=1)&&(seqNum>=lowWindow)&&(seqNum<=highWindow))
			{
				System.out.println("Segment " + seqNum + " must be resent");
				numResent++;
				processSend(transQueue.getSegment(seqNum),false);
			}
		}
		catch(NullPointerException e)
		{
			//Do nothing, this means that the node has already been removed since the ack has already been received
		}
	}
	
	public class TimeOutHandler extends TimerTask 
	{	
		private Segment sentSegment;
		private FastClient myClient;
		
		public TimeOutHandler(Segment sentSegment,String server_name, int server_port, int window, int timeout)
		{
			this.sentSegment = sentSegment;
		}
		
		public void run()
		{
			processTime(sentSegment.getSeqNum());
		}
	}

	public class ACKReceiveThread extends Thread
	{
		public void run()
		{
			byte ACK = -1;
			byte[] receiveData = new byte[1000]; 
			DatagramPacket receivePacket =  new DatagramPacket(receiveData,receiveData.length);
			Segment receiveSegment;
			Segment ackSeg;
			while(!(programDone))
			{
				try
				{
					clientSocket.receive(receivePacket);
				}
				catch(IOException e)
				{
					//Do nothing
				}
				if(receivePacket.getLength()>=4)
				{
					receiveSegment = new Segment(receivePacket);
					processAck(receiveSegment);
				}
			}
		}
	}
	

    /**
     * A simple test driver
     * 
     */
	public static void main(String[] args) {
		int window = 10; //segments
		int timeout = 100; // milli-seconds (don't change this value)
		
		String server = "localhost";
		String file_name = "";
		int server_port = 0;
		
		// check for command line arguments
		if (args.length == 4) {
			// either provide 3 parameters
			server = args[0];
			server_port = Integer.parseInt(args[1]);
			file_name = args[2];
			window = Integer.parseInt(args[3]);
		}
		else {
			System.out.println("wrong number of arguments, try again.");
			System.out.println("usage: java FastClient server port file windowsize");
			System.exit(0);
		}

		
		FastClient fc = new FastClient(server, server_port, window, timeout);
		
		//1.Open TCP connection with server
		try
		{
			Socket clientSocket = new Socket(server,server_port);	
			DataInputStream tcpInputStream = new DataInputStream(clientSocket.getInputStream());
			DataOutputStream tcpOutputStream = new DataOutputStream(clientSocket.getOutputStream());
			tcpOutputStream.writeUTF(file_name);
			tcpOutputStream.flush();
			
			//2.Initial handshake over TCP connection - send file name and receive response
			byte serverResponse = tcpInputStream.readByte();
			if(serverResponse!=0)
			{
				System.out.println("ERROR - Invalid server response");
				System.exit(0);
			}
			
			System.out.printf("sending file \'%s\' to server...\n", file_name);
			fc.send(file_name);
			System.out.println("file transfer completed.");
			
			//9.Send end-of-transmission message over TCP connection
			tcpOutputStream.writeByte(0);
			tcpOutputStream.flush();
			
			//10.Clean up - cancel timer, close socket and I/O streams
			tcpInputStream.close();
			tcpOutputStream.close();
			clientSocket.close();
			
			System.out.println("Packets transferred: " + numPackets);
			int totalPackets = numPackets+numResent;
			System.out.println("Number of packets sent: " + totalPackets);
		}
		catch (Exception e)
		{
			 //If any exception occurs, display it
			 System.out.println("Error " + e);
		}
		//11.Terminate program
		System.exit(0);
	}

}