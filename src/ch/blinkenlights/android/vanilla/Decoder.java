package ch.blinkenlights.android.vanilla;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.util.Log;

import org.jflac.FLACDecoder;
import org.jflac.frame.Frame;
import org.jflac.io.RandomFileInputStream;
import org.jflac.metadata.StreamInfo;
import org.jflac.util.ByteData;

import static android.media.MediaPlayer.MEDIA_ERROR_IO;
import static android.media.MediaPlayer.MEDIA_ERROR_UNKNOWN;

public class Decoder {

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

    private static final String TAG = "Decoder";
    private static final int MAX_BIT24 = 16777216;
    private static final float STEP_BIT24 = 2f / MAX_BIT24;

    private boolean decoding = false;
    private boolean starting = false;

	private AudioTrack mAudioTrack;
	private BitsPerSample mBitsPerSample;

	private String mSource;
	private int mSessionId = 0;
	private long mTotalSamples = 0;
	private long mBufferedSamples = 0;
    private int mPlaybackHeadPosition = 0;

    private Thread decoderThread = null;

	private MediaPlayer.OnErrorListener mOnErrorListener;
	private MediaPlayer.OnCompletionListener mOnCompletionListener;

	public Decoder(int sessionId) {
		mSessionId = sessionId;
	}

	public void stop() {
        Log.d(TAG, "stop()");

		stopAudioTrack();
		stopDecoding();
	}

    private void stopAudioTrack() {
        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack.flush();
            mPlaybackHeadPosition = 0;
        }
    }

	public void pause() {
        Log.d(TAG, "pause()");

		if (mAudioTrack != null) {
			mAudioTrack.pause();
		}

		stopDecoding();
	}

    private void stopDecoding() {
        if (decoderThread != null) {
            decoding = false;
            decoderThread.interrupt();

            try {
                decoderThread.join();
                decoderThread = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        mBufferedSamples = 0;
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
        Log.d(TAG, "seekTo(" + position + ")");

		if (mAudioTrack != null) {
			mAudioTrack.setPlaybackHeadPosition(position);
		}
	}

	// Track playback position in ms
	public int getCurrentPosition() {
		int result = 0;

		final int sampleRate = getSampleRate();
		if (mAudioTrack != null && sampleRate > 0) {
			final int headPosition = mAudioTrack.getPlaybackHeadPosition();
			result = samplesToMs(headPosition, sampleRate);
		}

		return result;
	}

	// Track duration in ms
	public int getDuration() {
		int result = 0;

		final int sampleRate = getSampleRate();
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
		if (isPlaying()) {
			result = mAudioTrack.getPlaybackRate();
		}

		return result;
	}

	public String getBufferInfo() {
		String result = "";

		final int sampleRate = getSampleRate();
		if (mAudioTrack != null && sampleRate > 0) {
			int bufferedMs = samplesToMs(mBufferedSamples, sampleRate);
			int position = getCurrentPosition();
			result = "Buffered ahead: " + (float) (bufferedMs - position) / 1000 + "s";
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
        Log.d(TAG, "setSource(" + source + ")");

        stopAudioTrack();
		stopDecoding();

        mSource = source;
		decoding = false;
		InputStream is = new FileInputStream(mSource);
		FLACDecoder decoder = new FLACDecoder(is);
		setupAudioTrack(decoder.readStreamInfo());
		is.close();
	}

	public void play() {
		stopDecoding();

        mBufferedSamples = 0;
		decoding = true;
		starting = true;

		decoderThread = new Thread(new Runnable() {
			@Override
			public void run() {
                Log.d(TAG, "decoderThread.run()>");
                InputStream is = null;
				try {
                    is = new RandomFileInputStream(mSource);
                    FLACDecoder decoder = new FLACDecoder(is);
                    decoder.seek(mPlaybackHeadPosition);
                    Log.d(TAG, "decoder.seek(" + mPlaybackHeadPosition +")");
                    Frame frame;
                    ByteData pcm = null;
                    while (decoding) {
                        frame = decoder.readNextFrame();
                        if (frame != null) {
                            pcm = decoder.decodeFrame(frame, pcm);
                            processPCM(pcm);
                        }
                        decoding = decoding && !decoder.isEOF() && frame != null;
                    }
				} catch (IOException ex) {
					if (mOnErrorListener != null) {
						mOnErrorListener.onError(null, MEDIA_ERROR_UNKNOWN, MEDIA_ERROR_IO);
					}
				}
                Log.d(TAG, "decoderThread.run()<");
			}
		});

		decoderThread.start();
	}

	private void setupAudioTrack(StreamInfo streamInfo) {
        Log.d(TAG, "setupAudioTrack()");
		if (mAudioTrack != null) {
			mAudioTrack.stop();
			mAudioTrack.flush();
			mAudioTrack.release();
		}

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
        Log.d(TAG, "processBytes(pcm) pcm len is " + dataSize);
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
        Log.d(TAG, "process24bits(pcm) pcm len is " + dataSize);
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
			int i0;
			int i1;
			int i2;
			float[] floats = new float[size];
			for (int i = 0; i < size; ++i) {
				offset = processed + i * 3;
				i0 = data[offset] & 0xFF;
				i1 = 255 * (data[offset + 1] & 0xFF);
				i2 = 65536 * data[offset + 2];
				floats[i] = STEP_BIT24 * (i2 + i1 + i0);
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