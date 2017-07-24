package ch.blinkenlights.android.vanilla;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;

import org.jflac.FLACDecoder;
import org.jflac.PCMProcessor;
import org.jflac.metadata.StreamInfo;
import org.jflac.util.ByteData;

import static android.media.MediaPlayer.MEDIA_ERROR_IO;
import static android.media.MediaPlayer.MEDIA_ERROR_UNKNOWN;

public class Decoder implements PCMProcessor {

	private enum BitsPerSample {
		UNKONOWN, BIT8, BIT16, BIT24, BIT32;

		public static BitsPerSample fromBitSize(int size) {
			BitsPerSample result = UNKONOWN;
			if (size == 8) {
				result = BIT8;
			} else if (size == 16) {
				result = BIT16;
			} else if (size == 24) {
				result = BIT24;
			} else if (size == 32) {
				result = BIT32;
			}

			return result;
		}
	}

	private AudioTrack mAudioTrack;
	private BitsPerSample mBitsPerSample;

	private String mSource;
	private int mSessionId = 0;
	private long mTotalSamples = 0;
	private long mBufferedSamples = 0;

	private MediaPlayer.OnErrorListener mOnErrorListener;
	private MediaPlayer.OnCompletionListener mOnCompletionListener;

	public Decoder(int sessionId) {
		mSessionId = sessionId;
	}

	public void stop() {
		decoding = false;
		if (mAudioTrack != null) {
			mAudioTrack.stop();
			mAudioTrack.flush();
			mAudioTrack.release();
			mAudioTrack = null;
		}
	}

	public void pause() {
		if (mAudioTrack != null) {
			mAudioTrack.pause();
		}
	}

	public boolean isPlaying() {
		boolean result = false;
		if (mAudioTrack != null && mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
			result = true;
		}

		return result;
	}

	public void setVolume(float gain) {
		if (mAudioTrack != null) {
			mAudioTrack.setVolume(gain);
		}
	}

	public void seekTo(int position) {
		if (mAudioTrack != null) {
			mAudioTrack.setPlaybackHeadPosition(position);
		}
	}

	// Track playback position in ms
	public int getCurrentPosition() {
		int result = 0;

		final int sampleRate = getPlaybackRate();
		if (mAudioTrack != null && sampleRate > 0) {
			final int headPosition = mAudioTrack.getPlaybackHeadPosition();
			result = samplesToMs(headPosition, sampleRate);
		}

		return result;
	}

	// Track duration in ms
	public int getDuration() {
		int result = 0;

		final int sampleRate = getPlaybackRate();
		if (sampleRate > 0) {
			result = samplesToMs(mTotalSamples, sampleRate);
		}

		return result;
	}

	public int getSampleRate() {
		int result = 0;
		if (mAudioTrack != null) {
			result = mAudioTrack.getSampleRate();
		}

		return result;
	}

	public int getPlaybackRate() {
		int result = 0;
		if (mAudioTrack != null) {
			result = mAudioTrack.getPlaybackRate();
		}

		return result;
	}

	public String getBufferInfo() {
		String result = "";

		final int sampleRate = getPlaybackRate();
		if (mAudioTrack != null && sampleRate > 0) {
			int bufferedMs = samplesToMs(mBufferedSamples, sampleRate);
			int position = getCurrentPosition();
			result = "Buffered ahead: " + (bufferedMs - position) + "ms";
		}

		return result;
	}

	private int samplesToMs(int samples, int sampleRate) {
		return samplesToMs(Long.valueOf(samples),  sampleRate);
	}

	private int samplesToMs(long samples, int sampleRate) {
		return (int) ((1000 * samples) / sampleRate);
	}

	public String getBitsPerSample() {
		String result = "Unknown";

		if (mBitsPerSample == BitsPerSample.BIT8) {
			result = "8bit";
		} else if (mBitsPerSample == BitsPerSample.BIT16) {
			result = "16bit";
		} else if (mBitsPerSample == BitsPerSample.BIT24) {
			result = "24bit";
		} else if (mBitsPerSample == BitsPerSample.BIT32) {
			result = "32bit";
		}

		return result;
	}

	public String getPlaybackBitsPerSample() {
		String result = "Unknown";
		if (mAudioTrack != null) {
			int format = mAudioTrack.getAudioFormat();
			if (format == AudioFormat.ENCODING_PCM_8BIT) {
				result = "8bit";
			} else if (format == AudioFormat.ENCODING_PCM_16BIT) {
				result = "16bit";
			} else if (format == AudioFormat.ENCODING_PCM_FLOAT) {
				result = "float";
			}
		}

		return result;
	}

	public int getAudioSessionId() {
		return mSessionId;
	}

	public void setAudioSessionId(int id) {
		mSessionId = id;
	}

	public void setOnErrorListener(MediaPlayer.OnErrorListener listener) {
		mOnErrorListener = listener;
	}


	public void setOnCompletionListener(MediaPlayer.OnCompletionListener listener) {
		mOnCompletionListener = listener;
	}

	public void setSource(String source) throws IOException {
		mSource = source;
		decoding = false;
		InputStream is = new FileInputStream(mSource);
		FLACDecoder decoder = new FLACDecoder(is);
		processStreamInfo(decoder.readStreamInfo());
		is.close();
	}

	public void play() {
		mBufferedSamples = 0;
		decoding = true;
		starting = true;
		Thread decoderThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					InputStream is = new FileInputStream(mSource);
					FLACDecoder decoder = new FLACDecoder(is);
					decoder.addPCMProcessor(Decoder.this);
					decoder.decode();
					decoding = false;
					decoder.removePCMProcessor(Decoder.this);
					is.close();
				} catch (IOException ex) {
					if (mOnErrorListener != null) {
						mOnErrorListener.onError(null, MEDIA_ERROR_UNKNOWN, MEDIA_ERROR_IO);
					}
				}
			}
		});

		decoderThread.start();

	}

	@Override
	public void processStreamInfo(StreamInfo streamInfo) {
		mTotalSamples = streamInfo.getTotalSamples();
		AudioFormat.Builder formatBuilder = new AudioFormat.Builder();
		int sampleRate = streamInfo.getSampleRate();
		formatBuilder.setSampleRate(sampleRate);
		int chanelCount = streamInfo.getChannels();
		int chanelMask = AudioFormat.CHANNEL_OUT_MONO;
		if (chanelCount == 2) {
			chanelMask = AudioFormat.CHANNEL_OUT_STEREO;
		}
		formatBuilder.setChannelMask(chanelMask);

		int bitsPerSample = streamInfo.getBitsPerSample();
		mBitsPerSample = BitsPerSample.fromBitSize(bitsPerSample);
		int encoding;
		if (bitsPerSample == 8) {
			encoding = AudioFormat.ENCODING_PCM_8BIT;
		} else if (bitsPerSample > 16) {
			encoding = AudioFormat.ENCODING_PCM_FLOAT;
		} else {
			encoding = AudioFormat.ENCODING_PCM_16BIT;
		}

		formatBuilder.setEncoding(encoding);
		formatBuilder.setChannelMask(chanelMask);

		AudioAttributes attributes = new AudioAttributes.Builder().build();
		int buffSize = AudioTrack.getMinBufferSize(sampleRate, chanelMask, encoding);
		mAudioTrack = new AudioTrack(attributes, formatBuilder.build(),
				9 * buffSize, AudioTrack.MODE_STREAM, mSessionId);
		mAudioTrack.setNotificationMarkerPosition((int) mTotalSamples);
		mAudioTrack.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
			@Override
			public void onMarkerReached(AudioTrack track) {
				if (mOnCompletionListener != null) {
					stop();
					mOnCompletionListener.onCompletion(null);
				}
			}

			@Override
			public void onPeriodicNotification(AudioTrack track) {
				// do nothing
			}
		});
	}

	private static final long MAX_24BIT = 8388607;
	private boolean decoding;
	private boolean starting = false;

	@Override
	public void processPCM(ByteData pcm) {
		if (mBitsPerSample == BitsPerSample.BIT8 || mBitsPerSample == BitsPerSample.BIT16) {
			processBytes(pcm);
		} else if (mBitsPerSample == BitsPerSample.BIT24) {
			process24bits(pcm);
		}
	}

	private void processBytes(ByteData pcm) {
		final byte[] data = pcm.getData();
		final int dataSize = pcm.getLen();
		final int buffSize = mAudioTrack.getBufferSizeInFrames();
		int processed = 0;
		while (decoding && processed < dataSize) {
			int size;
			if (dataSize - processed > buffSize) {
				size = buffSize;
			} else {
				size = dataSize - processed;
			}

			mAudioTrack.write(data, processed, size, AudioTrack.WRITE_BLOCKING);
			mBufferedSamples += size;
			processed += size;

			if (starting) {
				mAudioTrack.play();
				starting = false;
			}
		}
	}

	private void process24bits(ByteData pcm) {
		final byte[] data = pcm.getData();
		final int dataSize = pcm.getLen();
		final int buffSize = mAudioTrack.getBufferSizeInFrames();
		int processed = 0;
		while (decoding && processed < dataSize) {
			int size;
			if ((dataSize - processed) > 3*buffSize) {
				size = buffSize;
			} else {
				size = (dataSize - processed) / 3;
			}

			int offset;
			int byte0;
			int byte1;
			float int24bit;
			float[] floats = new float[size];
			for (int i = 0; i < size; ++i) {
				offset = processed + i * 3;
				byte0 = data[offset];
				byte1 = data[offset + 1];
				int24bit = data[offset + 2] * 65536 + byte1 * 256 + byte0;
				floats[i] = int24bit / MAX_24BIT;
			}

			mAudioTrack.write(floats, 0, size, AudioTrack.WRITE_BLOCKING);
			mBufferedSamples += size;
			processed += 3*size;

			if (starting) {
				mAudioTrack.play();
				starting = false;
			}
		}
	}
}