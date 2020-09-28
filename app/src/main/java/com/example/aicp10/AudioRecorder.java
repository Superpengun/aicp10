package com.example.aicp10;

import java.nio.ByteBuffer;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.sinovoice.sdk.audio.HciAudioBuffer;
import com.sinovoice.sdk.audio.HciAudioMetrics;
import com.sinovoice.sdk.audio.HciAudioSink;
import com.sinovoice.sdk.audio.HciAudioSource;
import com.sinovoice.sdk.audio.HciSampleFormat;

/**
 * 录音机音频源
 */
public class AudioRecorder {

	static public final int IDLE = 0;
	static public final int RUNNING = 1;
	static public final int CANCELLING = 2;
	static public final int STOPPING = 3;

	private final HciAudioMetrics metrics;
	private final int sampleRate;
	private final int audioFormat;
	private final int channelConfig;
	private final HciAudioBuffer ab;
	private AudioRecord device;
	private int state;

	// 从音频设备中读取数据，写入至 HciAudioBuffer 的音频槽中
	private final Runnable main = new Runnable() {

		@Override
		public void run() {
			HciAudioSink sink = ab.audioSink();
			int size = metrics.frameSize();
			// HciAudioSink 需要使用 Direct ByteBuffer
			ByteBuffer bb = ByteBuffer.allocateDirect(size);
			while (state == RUNNING) {
				bb.position(0);
				int ret = device.read(bb, size);
				if (ret > 0) {
					bb.limit(ret);
					bb.position(0);
				}
				if (sink.write(bb, false) < 0) {
					break;
				}
			}
			sink.endWrite(state != STOPPING);
			device.stop();
			device.release();
			device = null;
			sink.dispose();
			state = IDLE;
		}
	};

	public AudioRecorder(String audio_format, int slice, int buffer_time) {
		// 根据音频格式设置 HciAudioMetrics
		metrics = new HciAudioMetrics();
		if (audio_format.equals("pcm_s16le_16k")) {
			audioFormat = AudioFormat.ENCODING_PCM_16BIT;
			channelConfig = AudioFormat.CHANNEL_IN_MONO;
			metrics.setChannels(1);
			metrics.setFormat(HciSampleFormat.S16LE);
			metrics.setSampleRate(sampleRate = 16000);
			metrics.setFrameTime(slice);
		} else if (audio_format.equals("pcm_s16le_8k")) {
			audioFormat = AudioFormat.ENCODING_PCM_8BIT;
			channelConfig = AudioFormat.CHANNEL_IN_MONO;
			metrics.setChannels(1);
			metrics.setFormat(HciSampleFormat.S16LE);
			metrics.setSampleRate(sampleRate = 8000);
			metrics.setFrameTime(slice);
		} else {
			throw new RuntimeException("unsupported format: " + audio_format);
		}
		state = IDLE;
		ab = new HciAudioBuffer(metrics, buffer_time);
	}

	public int getState() {
		return state;
	}

	public HciAudioSource audioSource() {
		return ab;
	}

	public int bufferTimeLen() {
		return ab.bufferTimeLen();
	}

	public int bufferDataLen() {
		return ab.bufferDataLen();
	}

	/**
	 * 读取所有缓存的音频数据
	 * @return 返回缓存数据
	 */
	public ByteBuffer readAll() {
		// 通过 HciAudioSource 接口读取所有缓存的数据，HciAudioSource 不应在其他
		// 地方被使用
		HciAudioMetrics m = metrics.clone();
		if (ab.startRead(m) != 0) {
			return null;
		}
		int len = ab.bufferDataLen();
		ByteBuffer bb = ByteBuffer.allocateDirect(len);
		ab.read(bb, false);
		bb.flip();
		ab.endRead();
		return bb;
	}

	public void start() {
		int frameSize2 = metrics.frameSize() * 2;
		int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
		if (AudioRecord.ERROR == bufferSize || AudioRecord.ERROR_BAD_VALUE == bufferSize) {
			throw new RuntimeException("getMinBufferSize failed");
		}
		if (bufferSize < frameSize2) {
			bufferSize = frameSize2;
		}
		final HciAudioSink sink = ab.audioSink();
		synchronized (this) {
			if (state != IDLE) {
				throw new RuntimeException("running already");
			}
			// 创建设备
			device = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig,
					audioFormat, bufferSize);
			if (device.getState() != AudioRecord.STATE_INITIALIZED) {
				device.release();
				device = null;
				throw new RuntimeException("open device failed");
			}
			boolean device_started = false;
			boolean sink_started = false;
			// 开始录音
			device.startRecording();
			device_started = true;
			try {
				// 预先读取 50ms 数据
				int size = metrics.getDataLength(50);
				byte[] data = new byte[size];
				int len = device.read(data, 0, data.length);
				if (len <= 0) {
					throw new RuntimeException("read data failed");
				}
				HciAudioMetrics m = metrics.clone();
				if (sink.startWrite(m) != 0) {
					throw new RuntimeException("open audiosink failed");
				}
				sink_started = true;
				new Thread(main).start();
				state = RUNNING;
			} finally {
				if (state == IDLE) {
					if (device_started) {
						device.stop();
					}
					device.release();
					device = null;
					if (sink_started) {
						sink.endWrite(true);
					}
					sink.dispose();
				}
			}
		}
	}

	synchronized public void stop(boolean cancel) {
		if (state == RUNNING) {
			state = cancel ? CANCELLING : STOPPING;
		}
	}
}
