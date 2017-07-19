/*
 * Copyright (C) 2015 Adrian Ulrich <adrian@blinkenlights.ch>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>. 
 */


package ch.blinkenlights.android.vanilla;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.os.Build;

import java.io.IOException;

public class VanillaMediaPlayer {

	private Context mContext;
	private String mDataSource;
	private float mReplayGain = Float.NaN;
	private float mDuckingFactor = Float.NaN;
	private boolean mIsDucking = false;

	private VanillaMediaPlayer mNextMediaPlayer = null;

	private Decoder mDecoder;

	/**
	 * Constructs a new VanillaMediaPlayer class
	 */
	public VanillaMediaPlayer(Context context, int sessionId) {
		mContext = context;
		mDecoder = new Decoder(sessionId);
	}

	/**
	 * Resets the media player to an unconfigured state
	 */
	public void reset() {
		mDataSource = null;
		mNextMediaPlayer = null;
		mDecoder.stop();
	}

	/**
	 * Releases the media player and frees any claimed AudioEffect
	 */
	public void release() {
		mDataSource = null;
		mNextMediaPlayer = null;
		mDecoder.stop();
	}

	/**
	 * Sets the data source to use
	 */
	public void setDataSource(String path) throws IOException {
		mDataSource = path;
		mDecoder.setSource(mDataSource);
	}

	/**
	 * Returns the configured data source, may be null
	 */
	public String getDataSource() {
		return mDataSource;
	}

	/**
	 * Sets the next media player data source
	 */
	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	public void setNextMediaPlayer(VanillaMediaPlayer next) {
		mNextMediaPlayer = next;
	}

	/**
	 * Returns true if a 'next' media player has been configured
	 * via setNextMediaPlayer(next)
	 */
	public boolean hasNextMediaPlayer() {
		return mNextMediaPlayer != null;
	}

	/**
	 * Creates a new AudioEffect for our AudioSession
	 */
	public void openAudioFx() {
		Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
		i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, this.getAudioSessionId());
		i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, mContext.getPackageName());
		mContext.sendBroadcast(i);
	}

	/**
	 * Releases a previously claimed audio session id
	 */
	public void closeAudioFx() {
		Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
		i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, this.getAudioSessionId());
		i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, mContext.getPackageName());
		mContext.sendBroadcast(i);
	}

	/**
	 * Sets the desired scaling due to replay gain.
	 * @param replayGain the factor to adjust the volume by. Must be between 0 and 1 (inclusive)
	 *                    or {@link Float#NaN} to disable replay gain scaling
	 */
	public void setReplayGain(float replayGain) {
		mReplayGain = replayGain;
		updateVolume();
	}

	/**
	 * Sets whether we are ducking or not. Ducking is when we temporarily decrease the volume for
	 * a transient sound to play from another application, such as a notification's beep.
	 * @param isDucking true if we are ducking, false if we are not
	 */
	public void setIsDucking(boolean isDucking) {
		mIsDucking = isDucking;
		updateVolume();
	}

	/**
	 * Sets the desired scaling while ducking.
	 * @param duckingFactor the factor to adjust the volume by while ducking. Must be between 0
	 *                         and 1 (inclusive) or {@link Float#NaN} to disable ducking completely
	 *
	 * See also {@link #setIsDucking(boolean)}
	 */
	public void setDuckingFactor(float duckingFactor) {
		mDuckingFactor = duckingFactor;
		updateVolume();
	}
	/**
	 * Sets the volume, using the replay gain and ducking if appropriate
	 */
	private void updateVolume() {
		float volume = 1.0f;
		if (!Float.isNaN(mReplayGain)) {
			volume = mReplayGain;
		}
		if(mIsDucking && !Float.isNaN(mDuckingFactor)) {
			volume *= mDuckingFactor;
		}

		mDecoder.setVolume(volume);
	}

	public void setOnCompletionListener(MediaPlayer.OnCompletionListener listener) {
		mDecoder.setOnCompletionListener(listener);
	}

	public void setOnErrorListener(MediaPlayer.OnErrorListener listener) {
		mDecoder.setOnErrorListener(listener);
	}

	public int getCurrentPosition() {
		return mDecoder.getCurrentPosition();
	}

	public void start() {
		mDecoder.play();
	}

	public void pause() {
		mDecoder.pause();
	}

	public void stop() {
		mDecoder.stop();
	}

	public boolean isPlaying() {
		return mDecoder.isPlaying();
	}

	public void seekTo(int position) {
		mDecoder.seekTo(position);

	}

	public int getDuration() {
		return mDecoder.getDuration();
	}

	public int getSampleRate() {
		return mDecoder.getSampleRate();
	}

	public int getPlaybackRate() {
		return mDecoder.getPlaybackRate();
	}

	public String getBitsPerSample() {
		return mDecoder.getBitsPerSample();
	}

	public String getPlaybackBitsPerSample() {
		return mDecoder.getPlaybackBitsPerSample();
	}

	public int getAudioSessionId() {
		return mDecoder.getAudioSessionId();
	}

	public void setAudioSessionId(int id) {
		mDecoder.setAudioSessionId(id);
	}

	public void setAudioStreamType(int id) {
		// do nothing
	}

	public void prepare() {
		// do nothing
	}

	public void setmOnErrorListener(MediaPlayer.OnErrorListener listener) {
		mDecoder.setOnErrorListener(listener);
	}
}
