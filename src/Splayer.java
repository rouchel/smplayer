import java.io.DataInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.HashMap;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Obuffer;

class RamObuffer extends Obuffer {
	private byte[] buffer;
	private short[] bufferp;
	private int channels;
	private Fifo fifo;

	public RamObuffer(int number_of_channels, int freq, Fifo wFifo) {
		buffer = new byte[OBUFFERSIZE * 2];
		bufferp = new short[MAXCHANNELS];
		channels = number_of_channels;
		fifo = wFifo;

		for (int i = 0; i < number_of_channels; ++i)
			bufferp[i] = (short) i;
	}

	/**
	 * Takes a 16 Bit PCM sample.
	 */
	public void append(int channel, short value) {
		buffer[bufferp[channel] * 2] = (byte) (value & 0x00FF);
		buffer[bufferp[channel] * 2 + 1] = (byte) ((value >>> 8) & 0x00FF);
		bufferp[channel] += channels;
	}

	/**
	 * Write the samples to the file (Random Acces).
	 */
	short[] myBuffer = new short[2];

	public void write_buffer(int val) {
		while (-1 == fifo.write(buffer, bufferp[0] * 2)) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		for (int i = 0; i < channels; ++i)
			bufferp[i] = (short) i;
	}

	public void close() {
	}

	/**
   *
   */
	public void clear_buffer() {
	}

	/**
   *
   */
	public void set_stop_flag() {
	}
}

class Fifo {
	private byte[] buff;
	private int readIndex, writeIndex;
	private int length;
	private int used;
	private int lock;
	public AudioFormat format;

	// File file = new File("/tmp/xyz.wav");
	// RandomAccessFile raf;

	private void fifoLock() {
		while (lock == 1) {
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		lock = 1;
	}

	private void fifoUnlock() {
		lock = 0;
	}

	public Fifo(int size) {
		length = size;
		buff = new byte[length];
		readIndex = 0;
		writeIndex = 0;
		used = 0;
		lock = 0;

		// try {
		// raf = new RandomAccessFile(file, "rw");
		// } catch (FileNotFoundException e) {
		// // TODO Auto-generated catch block
		// e.printStackTrace();
		// }
	}

	public int write(byte[] inBuff, int size) {
		//
		// try {
		// raf.write(inBuff, 0, size);
		// } catch (IOException e) {
		// // TODO Auto-generated catch block
		// e.printStackTrace();
		// }
		//
		// return 0;
		//
		fifoLock();

		if (used + size > length) {
			fifoUnlock();
			return -1;
		}

		for (int i = 0; i < size; i++) {
			buff[writeIndex] = inBuff[i];
			writeIndex++;
			writeIndex %= length;
		}
		used += size;

		fifoUnlock();

		return size;
	}

	public int read(byte[] outBuff, int size) {
		fifoLock();

		if (used < size) {
			fifoUnlock();
			return -1;
		}

		for (int i = 0; i < size; i++) {
			outBuff[i] = buff[readIndex];
			readIndex++;
			readIndex %= length;
			used--;
		}

		fifoUnlock();

		return size;
	}

	public boolean isFull() {
		return used == length;
	}

	public boolean isEmpty() {
		return used == 0;
	}

	public int getLength() {
		return length;
	}
}

class HttpFile {
	private Fifo fifo;
	private URL url;
	private DataInputStream in;
	private PrintWriter out;
	private Socket skt;
	private HashMap<String, String> head;

	class DownloadThread extends Thread {
		private Fifo fifo;

		public DownloadThread(Fifo downFifo) {
			fifo = downFifo;
		}

		@Override
		public void run() {
			// TODO Auto-generated method stub
			super.run();
			Bitstream bitIn = new Bitstream(in);
			javazoom.jl.decoder.Header header;
			javazoom.jl.decoder.Decoder decoder = new javazoom.jl.decoder.Decoder();
			RamObuffer output;

			for (int frame = 0; frame < Integer.MAX_VALUE; frame++) {
				try {
					header = bitIn.readFrame();
					if (header == null)
						break;

					int channels = (header.mode() == javazoom.jl.decoder.Header.SINGLE_CHANNEL) ? 1
							: 2;
					int freq = header.frequency();
					if (fifo.format == null) {
						fifo.format = new AudioFormat(freq, 16, channels, true,
								false);
					}

					output = new RamObuffer(channels, freq, fifo);
					decoder.setOutputBuffer(output);

					decoder.decodeFrame(header, bitIn);

					bitIn.closeFrame();
				} catch (BitstreamException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (DecoderException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}

		}
	}

	public HttpFile(String path) {
		try {
			url = new URL(path);
			skt = new Socket(url.getHost(), 80);
			in = new DataInputStream(skt.getInputStream());
			out = new PrintWriter(skt.getOutputStream(), true);
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		head = new HashMap<String, String>();
		out.print("GET " + url.getPath() + " HTTP/1.0 \r\n\r\n");
		out.flush();
		getHead();

		int contenSize;
		contenSize = Integer.valueOf(getHeadInfo("Content-Length"));

		fifo = new Fifo(contenSize * 10);
		DownloadThread download = new DownloadThread(fifo);
		download.start();
	}

	private void parseHeadString(String headString) {
		String[] hash;

		if (headString.matches(".*:.*")) {
			hash = headString.split(": +");
			head.put(hash[0], hash[1]);
		}
	}

	private void getHead() {
		try {
			String headString;
			while ((headString = in.readLine()) != null) {
				if (headString.length() == 0) {
					break;
				}

				parseHeadString(headString);
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public String getHeadInfo(String headKey) {
		System.out.println(headKey);
		System.out.println(head.get(headKey));
		return head.get(headKey);
	}

	public Fifo getFifo() {
		return fifo;
	}
}

class WavPlayer {
	private AudioFormat format;
	private int bufferSize;
	private SourceDataLine line;
	private byte[] lineBuff;
	private Fifo fifo;

	public WavPlayer(Fifo wavFifo) {
		// byte[] buff = new byte[512];
		fifo = wavFifo;
		//
		// while (-1 == fifo.read(buff, buff.length)) {
		// try {
		// Thread.sleep(100);
		// } catch (InterruptedException e) {
		// // TODO Auto-generated catch block
		// e.printStackTrace();
		// }
		// }
		//
		// ByteArrayInputStream in = new ByteArrayInputStream(buff);
		//
		// try {
		// format = AudioSystem.getAudioFileFormat(in).getFormat();
		// } catch (UnsupportedAudioFileException e1) {
		// // TODO Auto-generated catch block
		// e1.printStackTrace();
		// } catch (IOException e1) {
		// // TODO Auto-generated catch block
		// e1.printStackTrace();
		// }
		while (fifo.format == null) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
		}

		format = fifo.format;

		bufferSize = format.getFrameSize()
				* Math.round(format.getSampleRate() / 10);

		DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

		try {
			line = (SourceDataLine) AudioSystem.getLine(info);
			line.open(format, bufferSize);
		} catch (LineUnavailableException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void play() {
		line.start();

		// read from net work and play back
		lineBuff = new byte[bufferSize];
		int readLen;
		while (true) {
			readLen = fifo.read(lineBuff, bufferSize);
			if (readLen != bufferSize) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				continue;
			}

			System.out.println("read len: " + readLen);

			line.write(lineBuff, 0, bufferSize);
		}
	}

	public void close() {
		line.drain();
		line.close();
	}
}

public class Splayer {
	HttpFile audioFile;
	Fifo audioFifo;
	WavPlayer wPlayer;

	public Splayer(String urlString) {
		audioFile = new HttpFile(urlString);
		audioFifo = audioFile.getFifo();
		wPlayer = new WavPlayer(audioFifo);
	}

	public void play() {
		wPlayer.play();
	}

	public static void main(String[] args) {
		// Splayer player = new Splayer("file:///wind.mp3");
		Splayer player = new Splayer("http://192.168.1.130/xyt.mp3");
		player.play();
	}
}
