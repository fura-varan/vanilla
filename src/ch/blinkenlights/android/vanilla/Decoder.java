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

    private boolean decoding;
    private boolean fillBuffer;
    private boolean startPlay;

    private DoubleBuffer bytesBuffer = new DoubleBuffer();
    private Thread decoderThread = null;
    private Thread feedThread = null;
    private int underruns = 0;

    private AudioTrack mAudioTrack;
    private BitsPerSample mBitsPerSample;

    private String mSource;
    private int mSessionId = 0;
    private long mTotalSamples = 0;
    private long mBufferedSamples = 0;
    private int mPlaybackHeadPosition = 0;
    private int mSamplesOffset = 0;

    private MediaPlayer.OnErrorListener mOnErrorListener;
    private MediaPlayer.OnCompletionListener mOnCompletionListener;

    public Decoder(int sessionId) {
        mSessionId = sessionId;
    }

    public void stop() {
        Log.d(TAG, "stop()");

        stopAudioTrack();
        if (mAudioTrack != null) {
            stopFeed();
            startDecoding();
        }
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
            mPlaybackHeadPosition = mAudioTrack.getPlaybackHeadPosition();
            mSamplesOffset = mPlaybackHeadPosition;
        }

        stopFeed();
        startDecoding();
    }

    private void stopFeed() {
        Log.d(TAG, "stopFeed()>");
        decoding = false;

        if (feedThread != null) {
            feedThread.interrupt();
            try {
                feedThread.join();
                feedThread = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        mBufferedSamples = 0;
        underruns = 0;
        Log.d(TAG, "stopFeed()<");
    }

    public boolean isPlaying() {
        boolean result = false;
        if (mAudioTrack != null && mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            result = true;
        }

        Log.d(TAG, "isPlaying() = " + result);
        return result;
    }

    public void setVolume(float gain) {
        if (mAudioTrack != null) {
            mAudioTrack.setVolume(gain);
        }
    }

    public void seekTo(int position) {
        Log.d(TAG, "seekTo(" + position +")");
        if (mAudioTrack != null) {
            mAudioTrack.setPlaybackHeadPosition(position);
        }
    }

    // Track playback position in ms
    public int getCurrentPosition() {
        int result = 0;

        if (mAudioTrack != null) {
            final int headPosition = mAudioTrack.getPlaybackHeadPosition();
            result = samplesToMs(headPosition);
        }

        //Log.d(TAG, "getCurrentPosition() = " + result);
        return result;
    }

    // Track duration in ms
    public int getDuration() {
        return samplesToMs(mTotalSamples);
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
        return "Buffered ahead: " + (float) bufferedAheadMs() / 1000 + "s, "
                 + "Underruns: " + underruns;
    }

    private int bufferedAheadMs() {
        int result = 0;

        int offsetMs = samplesToMs(mSamplesOffset);
        int bufferedMs = samplesToMs(mBufferedSamples);
        int position = getCurrentPosition();
        result = offsetMs + bufferedMs - position;

        return result;
    }

    private int samplesToMs(int samples) {
        return samplesToMs(Long.valueOf(samples));
    }

    private int samplesToMs(long samples) {
        int result = 0;
        final int sampleRate = getSampleRate();
        if (sampleRate > 0) {
            result = (int) ((1000 * samples) / sampleRate);
        }

        return result;
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
        stopFeed();
        stopDecoding();

        mSource = source;

        InputStream is = new FileInputStream(mSource);
        FLACDecoder decoder = new FLACDecoder(is);
        setupAudioTrack(decoder.readStreamInfo());
        is.close();

        startDecoding();
    }

    public void play() {
        Log.d(TAG, "play()");
        startPlay = true;
        if (feedThread == null) {
            startFeedAudioTrack();
        }
    }

    private void startFeedAudioTrack() {
        Log.d(TAG, "startFeedAudioTrack()");
        underruns = 0;

        feedThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "feedThread.run()>");
                while (decoding) {
                    // wait for fill
                    while (decoding && fillBuffer) {
                        int safeWaitTime = bufferedAheadMs() / 2;
                        int sleepMs;
                        if (safeWaitTime > 200) {
                            sleepMs = safeWaitTime;
                        } else {
                            sleepMs = 200;
                            underruns++;
                        }
                        try {
                            Log.d(TAG, "feedThread.sleep(" + sleepMs + ")");
                            Thread.sleep(sleepMs);
                        } catch (InterruptedException e) {
                            // re-check while condition
                        }
                    }

                    if (decoding) {
                        if (mBitsPerSample == BitsPerSample.BIT8 || mBitsPerSample == BitsPerSample.BIT16) {
                            Log.d(TAG, "feedThread switchBuffers()");
                            bytesBuffer.switchBuffers();
                            fillBuffer = true;
                            decoderThread.interrupt();
                            feedBytes();
                        } else if (mBitsPerSample == BitsPerSample.BIT24) {
                            // TODO: feed24bits(pcm);
                        }
                    }
                }
                Log.d(TAG, "feedThread.run()<");
            }
        });

        feedThread.start();
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
    }

    private void startDecoding() {
        Log.d(TAG, "startDecoding()>");

        stopDecoding();
        bytesBuffer.clean();
        mBufferedSamples = 0;
        decoding = true;
        fillBuffer = true;

        decoderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "decoderThread.run()>");
                InputStream is = null;
                try {
                    is = new RandomFileInputStream(mSource);
                    FLACDecoder decoder = new FLACDecoder(is);
                    Log.d(TAG, "decoder.seek(" + mPlaybackHeadPosition +")");
                    decoder.seek(mPlaybackHeadPosition);
                    Frame frame = decoder.readNextFrame();
                    ByteData pcm = decoder.decodeFrame(frame, null);
                    writePCM(pcm);
                    decoding = decoding && !decoder.isEOF() && frame != null;
                    while (decoding) {
                        while (!fillBuffer && decoding) {
                            try {
                                synchronized (decoderThread) {
                                    Log.d(TAG, "decoderThread: Waiting to fillBuffer");
                                    decoderThread.wait();
                                }
                            } catch (InterruptedException e) {
                                // check while condition again
                            }
                        }
                        Log.d(TAG, "decoderThread: Starting to fill buffer");
                        while (fillBuffer && decoding) {
                            frame = decoder.readNextFrame();
                            pcm = decoder.decodeFrame(frame, pcm);
                            writePCM(pcm);
                            decoding = decoding && !decoder.isEOF() && frame != null;
                        }
                    }
                    decoding = false;
                    is.close();
                } catch (IOException ex) {
                    try {
                        is.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    if (mOnErrorListener != null) {
                        mOnErrorListener.onError(null, MEDIA_ERROR_UNKNOWN, MEDIA_ERROR_IO);
                    }
                }
                Log.d(TAG, "decoderThread.run()<");
            }

            private void writePCM(ByteData pcm) {
                //Log.d(TAG, "writePCM(pcm), pcm.getLen() =" + pcm.getLen());
                if (mBitsPerSample == BitsPerSample.BIT8 || mBitsPerSample == BitsPerSample.BIT16) {
                    int len = pcm.getLen();
                    bytesBuffer.write(pcm.getData(), len);
                    int freeBytes = bytesBuffer.getFreeBytes();
                    if (freeBytes < len) {
                        Log.d(TAG, "writePCM(pcm): stop fill bytesBuffer, size = " + bytesBuffer.size);
                        fillBuffer = false;
                    }
                } else if (mBitsPerSample == BitsPerSample.BIT24) {
                    //TODO
                }
            }
        });

        decoderThread.start();
        Log.d(TAG, "startDecoding()<");
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

    private void feedBytes() {
        Log.d(TAG, "feedBytes() byteBuffer.size = " + bytesBuffer.size);
        final int dataSize = bytesBuffer.size;
        final int buffSize = mAudioTrack.getBufferSizeInFrames();
        int processed = 0;
        while (decoding && processed < dataSize) {
            int size;
            if (dataSize - processed > buffSize) {
                size = buffSize;
            } else {
                size = dataSize - processed;
            }

            mAudioTrack.write(bytesBuffer.bytes, processed, size, AudioTrack.WRITE_BLOCKING);
            mBufferedSamples += size;
            processed += size;

            if (startPlay) {
                mAudioTrack.play();
                startPlay = false;
            }
        }
    }

    private void feed24bits(ByteData pcm) {
        final byte[] data = pcm.getData();
        final int dataSize = pcm.getLen();
        final int buffSize = mAudioTrack.getBufferSizeInFrames();
        int processed = 0;
        while (decoding && processed < dataSize) {
            int size;
            if ((dataSize - processed) > 3 * buffSize) {
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
            processed += 3 * size;
        }
    }
}