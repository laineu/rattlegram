/*
Rattlegram

Copyright 2022 Ahmet Inan <inan@aicodix.de>
*/

package com.aicodix.rattlegram;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import com.aicodix.rattlegram.databinding.ActivityMainBinding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends AppCompatActivity {

	// Used to load the 'rattlegram' library on application startup.
	static {
		System.loadLibrary("rattlegram");
	}

	private final int permissionID = 1;
	private final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
	private final int sampleSize = 2;
	private TextView status;
	private AudioRecord audioRecord;
	private AudioTrack audioTrack;
	private int noiseSymbols;
	private int recordRate;
	private int outputRate;
	private int recordChannel;
	private int outputChannel;
	private int audioSource;
	private int carrierFrequency;
	private int recordCount;
	private short[] recordBuffer;
	private short[] outputBuffer;
	private Menu menu;
	private Handler handler;
	private Runnable statusTimer;
	private String prevStatus;
	private byte[] payload;
	private ArrayAdapter<String> messages;
	private float[] stagedCFO;
	private int[] stagedMode;
	private byte[] stagedCall;
	private String callSign;
	private String draftText;
	private String password;
	// IV (16) + length (1) + data (variable), limited to 170 bytes
	private final int MAX_MESSAGE_SIZE_AES = (int) Math.floor(170.f / 16.f) * 16;
	private final int MAX_CHARACTERS = MAX_MESSAGE_SIZE_AES - (16 + 1);

	private void setPassword() {
		View view = getLayoutInflater().inflate(R.layout.set_password, null);
		EditText passwordText = view.findViewById(R.id.password);
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setTitle(R.string.change_password);
		builder.setView(view);
		builder.setPositiveButton(R.string.okay, (dialog, which) -> {
			this.password = passwordText.getText().toString();
			storeSettings();
			addString(getString(R.string.password_changed));
		});
		builder.setNegativeButton(R.string.cancel, null);
		builder.show();
	}

	private IvParameterSpec generateIv() {
		byte[] iv = new byte[16];
		new SecureRandom().nextBytes(iv);
		return new IvParameterSpec(iv);
	}

	private SecretKey getKeyDerivation(String text) throws Exception {
		SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
		KeySpec spec = new PBEKeySpec(text.toCharArray(), text.getBytes(), 2048, 256);
		return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
	}

	private byte[] encryptText(String text) throws Exception {
		IvParameterSpec iv = generateIv();
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
		cipher.init(Cipher.ENCRYPT_MODE, getKeyDerivation(password), iv);
		byte[] finalBytes = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		output.write(iv.getIV());
		output.write((byte) finalBytes.length);
		output.write(finalBytes);
		return output.toByteArray();
	}

	// InputStream.readNBytes requires API level 33
	private byte[] readN(InputStream input, int n) throws Exception {
		byte[] temp = new byte[n];
		if (input.read(temp) != n)
			throw new Exception("Invalid data");
		return temp;
	}

	private byte[] decryptText(byte[] data) throws Exception {
		ByteArrayInputStream input = new ByteArrayInputStream(data);
		IvParameterSpec iv = new IvParameterSpec(readN(input, 16));
		int length = input.read();

		// AES/CBC/PKCS5PADDING is expected to be 16-byte aligned
		if (length <= 0 || length % 16 != 0)
			throw new Exception("Invalid length");

		data = readN(input, length);

		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
		cipher.init(Cipher.DECRYPT_MODE, getKeyDerivation(password), iv);
		return cipher.doFinal(data);
	}

	private native boolean createEncoder(int sampleRate);

	private native void configureEncoder(byte[] payload, byte[] callSign, int carrierFrequency, int noiseSymbols, boolean fancyHeader);

	private native boolean produceEncoder(short[] audioBuffer, int channelSelect);

	private native void destroyEncoder();

	private final AudioTrack.OnPlaybackPositionUpdateListener outputListener = new AudioTrack.OnPlaybackPositionUpdateListener() {
		@Override
		public void onMarkerReached(AudioTrack ignore) {

		}

		@Override
		public void onPeriodicNotification(AudioTrack audioTrack) {
			if (produceEncoder(outputBuffer, outputChannel)) {
				audioTrack.write(outputBuffer, 0, outputBuffer.length);
			} else {
				audioTrack.stop();
				handler.postDelayed(() -> startListening(), 1000);
			}
		}
	};

	private void initAudioTrack() {
		if (audioTrack != null) {
			boolean rateChanged = audioTrack.getSampleRate() != outputRate;
			boolean channelChanged = audioTrack.getChannelCount() != (outputChannel == 0 ? 1 : 2);
			if (!rateChanged && !channelChanged)
				return;
			audioTrack.stop();
			audioTrack.release();
		}
		int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
		int channelCount = 1;
		if (outputChannel != 0) {
			channelCount = 2;
			channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
		}
		int symbolLength = (1280 * outputRate) / 8000;
		int guardLength = symbolLength / 8;
		int extendedLength = symbolLength + guardLength;
		int bufferSize = 5 * extendedLength * sampleSize * channelCount;
		audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, outputRate, channelConfig, audioFormat, bufferSize, AudioTrack.MODE_STREAM);
		outputBuffer = new short[extendedLength * channelCount];
		audioTrack.setPlaybackPositionUpdateListener(outputListener);
		audioTrack.setPositionNotificationPeriod(extendedLength);
		if (!createEncoder(outputRate))
			setStatus(getString(R.string.heap_error));
	}

	private native boolean feedDecoder(short[] audioBuffer, int sampleCount, int channelSelect);

	private native int processDecoder();

	private native void stagedDecoder(float[] carrierFrequencyOffset, int[] operationMode, byte[] callSign);

	private native int fetchDecoder(byte[] payload);

	private native boolean createDecoder(int sampleRate);

	private native void destroyDecoder();

	private final AudioRecord.OnRecordPositionUpdateListener recordListener = new AudioRecord.OnRecordPositionUpdateListener() {
		@Override
		public void onMarkerReached(AudioRecord ignore) {

		}

		@Override
		public void onPeriodicNotification(AudioRecord audioRecord) {
			audioRecord.read(recordBuffer, 0, recordBuffer.length);
			if (!feedDecoder(recordBuffer, recordCount, recordChannel))
				return;
			int status = processDecoder();
			final int STATUS_OKAY = 0;
			final int STATUS_FAIL = 1;
			final int STATUS_SYNC = 2;
			final int STATUS_DONE = 3;
			final int STATUS_HEAP = 4;
			final int STATUS_NOPE = 5;
			// final int STATUS_PING = 6;
			switch (status) {
				case STATUS_OKAY:
					break;
				case STATUS_FAIL:
					setStatus(getString(R.string.preamble_fail), true);
					break;
				case STATUS_NOPE:
					stagedDecoder(stagedCFO, stagedMode, stagedCall);
					fromStatus();
					addLine(new String(stagedCall).trim(), getString(R.string.preamble_nope, stagedMode[0]));
					break;
				/* ping is encrypted as well
				case STATUS_PING:
					stagedDecoder(stagedCFO, stagedMode, stagedCall);
					fromStatus();
					addLine(new String(stagedCall).trim(), getString(R.string.preamble_ping));
					break;
				 */
				case STATUS_HEAP:
					setStatus(getString(R.string.heap_error));
					audioRecord.stop();
					break;
				case STATUS_SYNC:
					stagedDecoder(stagedCFO, stagedMode, stagedCall);
					fromStatus();
					break;
				case STATUS_DONE:
					int result = fetchDecoder(payload);
					if (result < 0) {
						addLine(new String(stagedCall).trim(), getString(R.string.decoding_failed));
					} else {
						setStatus(getResources().getQuantityString(R.plurals.bits_flipped, result, result), true);
						try {
							// make sure NOT to overwrite the previous "payload", because doing so
							// will cause the size of "payload" to be different than expected
							byte[] newPayload = decryptText(payload);
							if (newPayload.length > 0) {
								// show the decrypted message
								addMessage(new String(stagedCall).trim(), getString(R.string.received), new String(newPayload).trim());
							} else {
								// empty is a ping
								addLine(new String(stagedCall).trim(), getString(R.string.preamble_ping));
							}
						} catch (Exception e) {
							// show the original message even if decryption fails (maybe it wasn't encrypted)
							// TODO: add a way to decrypt it with another password?
							addMessage(new String(stagedCall).trim(), getString(R.string.received_decrypt_failed), new String(payload).trim());
							return;
						}
					}
					break;
			}
		}
	};

	private void setStatus(String str, boolean tmp) {
		if (statusTimer != null)
			handler.removeCallbacks(statusTimer);
		if (tmp) {
			statusTimer = () -> status.setText(prevStatus);
			handler.postDelayed(statusTimer, 10000);
		} else {
			prevStatus = str;
		}
		status.setText(str);
	}

	private void setStatus(String str) {
		setStatus(str, false);
	}

	private void fromStatus() {
		setStatus(getString(R.string.from_status, new String(stagedCall).trim(), stagedMode[0], stagedCFO[0]), true);
	}

	private byte[] callTerm() {
		return Arrays.copyOf(callSign.getBytes(StandardCharsets.US_ASCII), callSign.length() + 1);
	}

	private String currentTime() {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
	}

	private void addLine(String call, String info) {
		addString(getString(R.string.title_line, currentTime(), call, info));
	}

	private void addMessage(String call, String info, String mesg) {
		addString(getString(R.string.title_message, currentTime(), call, info, mesg));
	}

	private void addString(String str) {
		int count = 100;
		if (messages.getCount() >= count)
			messages.remove(messages.getItem(count - 1));
		messages.insert(str, 0);
		storeSettings();
	}

	private void startListening() {
		if (audioRecord != null) {
			audioRecord.startRecording();
			if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
				audioRecord.read(recordBuffer, 0, recordBuffer.length);
				setStatus(getString(R.string.listening));
			} else {
				setStatus(getString(R.string.audio_recording_error));
			}
		}
	}

	private void stopListening() {
		if (audioRecord != null)
			audioRecord.stop();
	}

	private void initAudioRecord(boolean restart) {
		if (audioRecord != null) {
			boolean rateChanged = audioRecord.getSampleRate() != recordRate;
			boolean channelChanged = audioRecord.getChannelCount() != (recordChannel == 0 ? 1 : 2);
			boolean sourceChanged = audioRecord.getAudioSource() != audioSource;
			if (!rateChanged && !channelChanged && !sourceChanged)
				return;
			stopListening();
			audioRecord.release();
			audioRecord = null;
		}
		int channelConfig = AudioFormat.CHANNEL_IN_MONO;
		int channelCount = 1;
		if (recordChannel != 0) {
			channelCount = 2;
			channelConfig = AudioFormat.CHANNEL_IN_STEREO;
		}
		int frameSize = sampleSize * channelCount;
		int bufferSize = 2 * Integer.highestOneBit(3 * recordRate) * frameSize;
		try {
			AudioRecord testAudioRecord = new AudioRecord(audioSource, recordRate, channelConfig, audioFormat, bufferSize);
			if (testAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
				if (createDecoder(recordRate)) {
					audioRecord = testAudioRecord;
					recordCount = recordRate / 50;
					recordBuffer = new short[recordCount * channelCount];
					audioRecord.setRecordPositionUpdateListener(recordListener);
					audioRecord.setPositionNotificationPeriod(recordCount);
					if (restart)
						startListening();
				} else {
					testAudioRecord.release();
					setStatus(getString(R.string.heap_error));
				}
			} else {
				testAudioRecord.release();
				setStatus(getString(R.string.audio_init_failed));
			}
		} catch (IllegalArgumentException e) {
			setStatus(getString(R.string.audio_setup_failed));
		} catch (SecurityException e) {
			setStatus(getString(R.string.audio_permission_denied));
		}
	}

	private void setRecordRate(int newSampleRate) {
		if (recordRate == newSampleRate)
			return;
		recordRate = newSampleRate;
		updateRecordRateMenu();
		initAudioRecord(true);
	}

	private void setRecordChannel(int newChannelSelect) {
		if (recordChannel == newChannelSelect)
			return;
		recordChannel = newChannelSelect;
		updateRecordChannelMenu();
		initAudioRecord(true);
	}

	private void setAudioSource(int newAudioSource) {
		if (audioSource == newAudioSource)
			return;
		audioSource = newAudioSource;
		updateAudioSourceMenu();
		initAudioRecord(true);
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode != permissionID)
			return;
		for (int i = 0; i < permissions.length; ++i)
			if (permissions[i].equals(Manifest.permission.RECORD_AUDIO) && grantResults[i] == PackageManager.PERMISSION_GRANTED)
				initAudioRecord(false);
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle state) {
		state.putInt("outputRate", outputRate);
		state.putInt("outputChannel", outputChannel);
		state.putInt("recordRate", recordRate);
		state.putInt("recordChannel", recordChannel);
		state.putInt("audioSource", audioSource);
		state.putInt("carrierFrequency", carrierFrequency);
		state.putInt("noiseSymbols", noiseSymbols);
		state.putString("callSign", callSign);
		state.putString("draftText", draftText);
		state.putString("password", password);
		for (int i = 0; i < messages.getCount(); ++i)
			state.putString("m" + i, messages.getItem(i));
		super.onSaveInstanceState(state);
	}

	private void storeSettings() {
		SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
		SharedPreferences.Editor edit = pref.edit();
		edit.putInt("outputRate", outputRate);
		edit.putInt("outputChannel", outputChannel);
		edit.putInt("recordRate", recordRate);
		edit.putInt("recordChannel", recordChannel);
		edit.putInt("audioSource", audioSource);
		edit.putInt("carrierFrequency", carrierFrequency);
		edit.putInt("noiseSymbols", noiseSymbols);
		edit.putString("callSign", callSign);
		edit.putString("draftText", draftText);
		edit.putString("password", password);
		for (int i = 0; i < messages.getCount(); ++i)
			edit.putString("m" + i, messages.getItem(i));
		edit.apply();
	}

	@Override
	protected void onCreate(Bundle state) {
		messages = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
		final int defaultSampleRate = 8000;
		final int defaultChannelSelect = 0;
		final int defaultAudioSource = MediaRecorder.AudioSource.DEFAULT;
		final int defaultCarrierFrequency = 1500;
		final int defaultNoiseSymbols = 6;
		final String defaultCallSign = "ANONYMOUS";
		final String defaultDraftText = "";
		final String defaultPassword = "password";
		if (state == null) {
			SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.getDefaultNightMode());
			outputRate = pref.getInt("outputRate", defaultSampleRate);
			outputChannel = pref.getInt("outputChannel", defaultChannelSelect);
			recordRate = pref.getInt("recordRate", defaultSampleRate);
			recordChannel = pref.getInt("recordChannel", defaultChannelSelect);
			audioSource = pref.getInt("audioSource", defaultAudioSource);
			carrierFrequency = pref.getInt("carrierFrequency", defaultCarrierFrequency);
			noiseSymbols = pref.getInt("noiseSymbols", defaultNoiseSymbols);
			callSign = pref.getString("callSign", defaultCallSign);
			draftText = pref.getString("draftText", defaultDraftText);
			password = pref.getString("password", defaultPassword);
			for (int i = 0; i < 100; ++i) {
				String mesg = pref.getString("m" + i, null);
				if (mesg != null)
					messages.add(mesg);
			}
		} else {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.getDefaultNightMode());
			outputRate = state.getInt("outputRate", defaultSampleRate);
			outputChannel = state.getInt("outputChannel", defaultChannelSelect);
			recordRate = state.getInt("recordRate", defaultSampleRate);
			recordChannel = state.getInt("recordChannel", defaultChannelSelect);
			audioSource = state.getInt("audioSource", defaultAudioSource);
			carrierFrequency = state.getInt("carrierFrequency", defaultCarrierFrequency);
			noiseSymbols = state.getInt("noiseSymbols", defaultNoiseSymbols);
			callSign = state.getString("callSign", defaultCallSign);
			draftText = state.getString("draftText", defaultDraftText);
			password = state.getString("password", defaultPassword);
			for (int i = 0; i < 100; ++i) {
				String mesg = state.getString("m" + i, null);
				if (mesg != null)
					messages.add(mesg);
			}
		}
		super.onCreate(state);
		ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
		status = binding.status;
		handler = new Handler(getMainLooper());
		setContentView(binding.getRoot());
		stagedCFO = new float[1];
		stagedMode = new int[1];
		stagedCall = new byte[10];
		payload = new byte[170];
		binding.messages.setAdapter(messages);
		binding.messages.setOnItemClickListener((adapterView, view, i, l) -> {
			String item = messages.getItem(i);
			if (item != null) {
				String[] mesg = item.split("\n", 2);
				if (mesg.length == 2)
					composeMessage(mesg[1]);
			}
		});
		binding.messages.setOnItemLongClickListener((adapterView, view, i, l) -> {
			String item = messages.getItem(i);
			if (item != null) {
				String[] mesg = item.split("\n", 2);
				if (mesg.length == 2)
					transmitMessage(mesg[1]);
			}
			return true;
		});
		initAudioTrack();

		List<String> permissions = new ArrayList<>();
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			permissions.add(Manifest.permission.RECORD_AUDIO);
			setStatus(getString(R.string.audio_permission_denied));
		} else {
			initAudioRecord(false);
		}
		if (!permissions.isEmpty())
			ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), permissionID);

		String message = extractIntent(getIntent());
		if (message != null)
			composeMessage(message);
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		String message = extractIntent(intent);
		if (message != null)
			composeMessage(message);
	}

	private String extractIntent(Intent intent) {
		String action = intent.getAction();
		if (action == null)
			return null;
		if (!action.equals(Intent.ACTION_SEND))
			return null;
		String type = intent.getType();
		if (type == null)
			return null;
		if (!type.equals("text/plain"))
			return null;
		return intent.getStringExtra(Intent.EXTRA_TEXT);
	}

	private void setNoiseSymbols(int newNoiseSymbols) {
		if (noiseSymbols == newNoiseSymbols)
			return;
		noiseSymbols = newNoiseSymbols;
		updateNoiseSymbolsMenu();
	}

	private void updateNoiseSymbolsMenu() {
		switch (noiseSymbols) {
			case 0:
				menu.findItem(R.id.action_disable_noise).setChecked(true);
				break;
			case 1:
				menu.findItem(R.id.action_set_noise_quarter_second).setChecked(true);
				break;
			case 3:
				menu.findItem(R.id.action_set_noise_half_second).setChecked(true);
				break;
			case 6:
				menu.findItem(R.id.action_set_noise_one_second).setChecked(true);
				break;
			case 11:
				menu.findItem(R.id.action_set_noise_two_seconds).setChecked(true);
				break;
			case 22:
				menu.findItem(R.id.action_set_noise_four_seconds).setChecked(true);
				break;
		}
	}

	private void setOutputRate(int newSampleRate) {
		if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
			return;
		if (outputRate == newSampleRate)
			return;
		outputRate = newSampleRate;
		updateOutputRateMenu();
		initAudioTrack();
	}

	private void updateOutputRateMenu() {
		switch (outputRate) {
			case 8000:
				menu.findItem(R.id.action_set_output_rate_8000).setChecked(true);
				break;
			case 16000:
				menu.findItem(R.id.action_set_output_rate_16000).setChecked(true);
				break;
			case 32000:
				menu.findItem(R.id.action_set_output_rate_32000).setChecked(true);
				break;
			case 44100:
				menu.findItem(R.id.action_set_output_rate_44100).setChecked(true);
				break;
			case 48000:
				menu.findItem(R.id.action_set_output_rate_48000).setChecked(true);
				break;
		}
	}

	private void updateRecordRateMenu() {
		switch (recordRate) {
			case 8000:
				menu.findItem(R.id.action_set_record_rate_8000).setChecked(true);
				break;
			case 16000:
				menu.findItem(R.id.action_set_record_rate_16000).setChecked(true);
				break;
			case 32000:
				menu.findItem(R.id.action_set_record_rate_32000).setChecked(true);
				break;
			case 44100:
				menu.findItem(R.id.action_set_record_rate_44100).setChecked(true);
				break;
			case 48000:
				menu.findItem(R.id.action_set_record_rate_48000).setChecked(true);
				break;
		}
	}

	private void updateRecordChannelMenu() {
		switch (recordChannel) {
			case 0:
				menu.findItem(R.id.action_set_record_channel_default).setChecked(true);
				break;
			case 1:
				menu.findItem(R.id.action_set_record_channel_first).setChecked(true);
				break;
			case 2:
				menu.findItem(R.id.action_set_record_channel_second).setChecked(true);
				break;
			case 3:
				menu.findItem(R.id.action_set_record_channel_summation).setChecked(true);
				break;
			case 4:
				menu.findItem(R.id.action_set_record_channel_analytic).setChecked(true);
				break;
		}
	}

	private void updateAudioSourceMenu() {
		switch (audioSource) {
			case MediaRecorder.AudioSource.DEFAULT:
				menu.findItem(R.id.action_set_source_default).setChecked(true);
				break;
			case MediaRecorder.AudioSource.MIC:
				menu.findItem(R.id.action_set_source_microphone).setChecked(true);
				break;
			case MediaRecorder.AudioSource.CAMCORDER:
				menu.findItem(R.id.action_set_source_camcorder).setChecked(true);
				break;
			case MediaRecorder.AudioSource.VOICE_RECOGNITION:
				menu.findItem(R.id.action_set_source_voice_recognition).setChecked(true);
				break;
			case MediaRecorder.AudioSource.UNPROCESSED:
				menu.findItem(R.id.action_set_source_unprocessed).setChecked(true);
				break;
		}
	}

	private void setOutputChannel(int newChannelSelect) {
		if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
			return;
		if (outputChannel == newChannelSelect)
			return;
		outputChannel = newChannelSelect;
		updateOutputChannelMenu();
		initAudioTrack();
	}

	private void updateOutputChannelMenu() {
		switch (outputChannel) {
			case 0:
				menu.findItem(R.id.action_set_output_channel_default).setChecked(true);
				break;
			case 1:
				menu.findItem(R.id.action_set_output_channel_first).setChecked(true);
				break;
			case 2:
				menu.findItem(R.id.action_set_output_channel_second).setChecked(true);
				break;
			case 4:
				menu.findItem(R.id.action_set_output_channel_analytic).setChecked(true);
				break;
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.menu_main, menu);
		menu.findItem(R.id.action_set_source_unprocessed).setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
		this.menu = menu;
		updateOutputRateMenu();
		updateOutputChannelMenu();
		updateRecordRateMenu();
		updateRecordChannelMenu();
		updateAudioSourceMenu();
		updateNoiseSymbolsMenu();
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_ping) {
			transmitMessage("");
			return true;
		}
		if (id == R.id.action_delete_messages) {
			if (messages.getCount() > 0)
				deleteMessages();
			return true;
		}
		if (id == R.id.action_compose) {
			composeMessage(null);
			return true;
		}
		if (id == R.id.action_set_output_rate_8000) {
			setOutputRate(8000);
			return true;
		}
		if (id == R.id.action_set_output_rate_16000) {
			setOutputRate(16000);
			return true;
		}
		if (id == R.id.action_set_output_rate_32000) {
			setOutputRate(32000);
			return true;
		}
		if (id == R.id.action_set_output_rate_44100) {
			setOutputRate(44100);
			return true;
		}
		if (id == R.id.action_set_output_rate_48000) {
			setOutputRate(48000);
			return true;
		}
		if (id == R.id.action_set_output_channel_default) {
			setOutputChannel(0);
			return true;
		}
		if (id == R.id.action_set_output_channel_first) {
			setOutputChannel(1);
			return true;
		}
		if (id == R.id.action_set_output_channel_second) {
			setOutputChannel(2);
			return true;
		}
		if (id == R.id.action_set_output_channel_analytic) {
			setOutputChannel(4);
			return true;
		}
		if (id == R.id.action_set_record_rate_8000) {
			setRecordRate(8000);
			return true;
		}
		if (id == R.id.action_set_record_rate_16000) {
			setRecordRate(16000);
			return true;
		}
		if (id == R.id.action_set_record_rate_32000) {
			setRecordRate(32000);
			return true;
		}
		if (id == R.id.action_set_record_rate_44100) {
			setRecordRate(44100);
			return true;
		}
		if (id == R.id.action_set_record_rate_48000) {
			setRecordRate(48000);
			return true;
		}
		if (id == R.id.action_set_record_channel_default) {
			setRecordChannel(0);
			return true;
		}
		if (id == R.id.action_set_record_channel_first) {
			setRecordChannel(1);
			return true;
		}
		if (id == R.id.action_set_record_channel_second) {
			setRecordChannel(2);
			return true;
		}
		if (id == R.id.action_set_record_channel_summation) {
			setRecordChannel(3);
			return true;
		}
		if (id == R.id.action_set_record_channel_analytic) {
			setRecordChannel(4);
			return true;
		}
		if (id == R.id.action_set_source_default) {
			setAudioSource(MediaRecorder.AudioSource.DEFAULT);
			return true;
		}
		if (id == R.id.action_set_source_microphone) {
			setAudioSource(MediaRecorder.AudioSource.MIC);
			return true;
		}
		if (id == R.id.action_set_source_camcorder) {
			setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
			return true;
		}
		if (id == R.id.action_set_source_voice_recognition) {
			setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
			return true;
		}
		if (id == R.id.action_set_source_unprocessed) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				setAudioSource(MediaRecorder.AudioSource.UNPROCESSED);
				return true;
			}
			return false;
		}
		if (id == R.id.action_edit_call_sign) {
			editCallSign();
			return true;
		}
		if (id == R.id.action_set_carrier_frequency) {
			setCarrierFrequency();
			return true;
		}
		if (id == R.id.action_disable_noise) {
			setNoiseSymbols(0);
			return true;
		}
		if (id == R.id.action_set_noise_quarter_second) {
			setNoiseSymbols(1);
			return true;
		}
		if (id == R.id.action_set_noise_half_second) {
			setNoiseSymbols(3);
			return true;
		}
		if (id == R.id.action_set_noise_one_second) {
			setNoiseSymbols(6);
			return true;
		}
		if (id == R.id.action_set_noise_two_seconds) {
			setNoiseSymbols(11);
			return true;
		}
		if (id == R.id.action_set_noise_four_seconds) {
			setNoiseSymbols(22);
			return true;
		}
		if (id == R.id.action_force_quit) {
			forcedQuit();
			return true;
		}
		if (id == R.id.action_password) {
			setPassword();
			return true;
		}
		if (id == R.id.action_privacy_policy) {
			showTextPage(getString(R.string.privacy_policy), getString(R.string.privacy_policy_text));
			return true;
		}
		if (id == R.id.action_about) {
			showTextPage(getString(R.string.about), getString(R.string.about_text, BuildConfig.VERSION_NAME));
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void deleteMessages() {
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setTitle(R.string.delete_messages)
				.setMessage(R.string.delete_messages_prompt)
				.setPositiveButton(R.string.delete, (dialog, which) -> {
					messages.clear();
					SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
					SharedPreferences.Editor editor = pref.edit();
					for (int i = 0; i < 100; ++i)
						editor.remove("m" + i);
					editor.apply();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void forcedQuit() {
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setTitle(R.string.force_quit)
				.setMessage(R.string.force_quit_prompt)
				.setPositiveButton(R.string.quit, (dialog, which) -> {
					storeSettings();
					System.exit(0);
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void composeMessage(String temp) {
		View view = getLayoutInflater().inflate(R.layout.compose_message, null);
		EditText edit = view.findViewById(R.id.message);
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setTitle(R.string.compose_message);
		builder.setView(view);
		builder.setNeutralButton(R.string.draft, (dialogInterface, i) -> draftText = edit.getText().toString());
		builder.setNegativeButton(R.string.discard, (dialogInterface, i) -> {
			if (temp == null)
				draftText = "";
		});
		builder.setPositiveButton(R.string.transmit, (dialogInterface, i) -> {
			if (temp == null)
				draftText = "";
			transmitMessage(edit.getText().toString());
		});
		builder.setOnCancelListener(dialogInterface -> {
			String text = edit.getText().toString();
			if (temp == null || !temp.equals(text))
				draftText = text;
		});
		AlertDialog dialog = builder.show();
		TextView left = view.findViewById(R.id.capacity);
		Context context = this;
		edit.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

			}

			@Override
			public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
				String text = charSequence.toString();
				int bytes = text.getBytes(StandardCharsets.UTF_8).length;
				int estimated = MAX_CHARACTERS - bytes;
				if (bytes <= MAX_CHARACTERS) {
					left.setText(getResources().getQuantityString(R.plurals.characters_left, estimated, estimated));
					left.setTextColor(ContextCompat.getColor(context, R.color.tint));
					dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(bytes > 0);
				} else {
					left.setText(getResources().getQuantityString(R.plurals.over_capacity, estimated, estimated));
					left.setTextColor(Color.RED);
					dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
				}
			}

			@Override
			public void afterTextChanged(Editable editable) {

			}
		});
		edit.setText(temp == null ? draftText : temp);
	}

	private void transmitMessage(String message) {
		stopListening();
		byte[] mesg;
		try {
			mesg = encryptText(message);
		} catch (Exception e) {
			Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
			return;
		}
		if (message.isEmpty())
			addLine(callSign.trim(), getString(R.string.sent_ping));
		else
			addMessage(callSign.trim(), getString(R.string.transmitted), message);
		configureEncoder(mesg, callTerm(), carrierFrequency, noiseSymbols, false);
		for (int i = 0; i < 5; ++i) {
			produceEncoder(outputBuffer, outputChannel);
			audioTrack.write(outputBuffer, 0, outputBuffer.length);
		}
		audioTrack.play();
		setStatus(getString(R.string.transmitting));
	}

	private String[] carrierValues(int minCarrierFrequency, int maxCarrierFrequency) {
		int count = (maxCarrierFrequency - minCarrierFrequency) / 50 + 1;
		String[] values = new String[count];
		for (int i = 0; i < count; ++i)
			values[i] = String.format(Locale.US, "%d", i * 50 + minCarrierFrequency);
		return values;
	}

	private void setInputType(ViewGroup np, int it) {
		int count = np.getChildCount();
		for (int i = 0; i < count; i++) {
			final View child = np.getChildAt(i);
			if (child instanceof ViewGroup) {
				setInputType((ViewGroup) child, it);
			} else if (child instanceof EditText) {
				EditText et = (EditText) child;
				et.setInputType(it);
				break;
			}
		}
	}

	private void setCarrierFrequency() {
		View view = getLayoutInflater().inflate(R.layout.carrier_frequency, null);
		NumberPicker picker = view.findViewById(R.id.carrier);
		int maxCarrierFrequency = 3000;
		int minCarrierFrequency = outputChannel == 4 ? -maxCarrierFrequency : 1000;
		if (carrierFrequency < minCarrierFrequency || carrierFrequency > maxCarrierFrequency)
			carrierFrequency = 1500;
		picker.setMinValue(0);
		picker.setDisplayedValues(null);
		picker.setMaxValue((maxCarrierFrequency - minCarrierFrequency) / 50);
		picker.setValue((carrierFrequency - minCarrierFrequency) / 50);
		picker.setDisplayedValues(carrierValues(minCarrierFrequency, maxCarrierFrequency));
		setInputType(picker, InputType.TYPE_CLASS_NUMBER);
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setTitle(R.string.carrier_frequency);
		builder.setView(view);
		builder.setNegativeButton(R.string.cancel, null);
		builder.setPositiveButton(R.string.okay, (dialogInterface, i) -> carrierFrequency = picker.getValue() * 50 + minCarrierFrequency);
		builder.show();
	}


	private void editCallSign() {
		View view = getLayoutInflater().inflate(R.layout.call_sign, null);
		EditText edit = view.findViewById(R.id.call);
		edit.setText(callSign);
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setTitle(R.string.call_sign);
		builder.setView(view);
		builder.setNegativeButton(R.string.cancel, null);
		builder.setPositiveButton(R.string.okay, (dialogInterface, i) -> callSign = edit.getText().toString());
		builder.show();
	}

	private void showTextPage(String title, String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setNeutralButton(R.string.close, null);
		builder.setTitle(title);
		builder.setMessage(message);
		builder.show();
	}

	@Override
	protected void onResume() {
		startListening();
		super.onResume();
	}

	@Override
	protected void onPause() {
		stopListening();
		storeSettings();
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		audioTrack.stop();
		destroyEncoder();
		destroyDecoder();
		super.onDestroy();
	}
}
