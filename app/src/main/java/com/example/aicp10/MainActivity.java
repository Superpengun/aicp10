package com.example.aicp10;

import java.io.File;
import java.nio.ByteBuffer;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.sinovoice.sdk.HciSdk;
import com.sinovoice.sdk.HciSdkConfig;
import com.sinovoice.sdk.IVoidCB;
import com.sinovoice.sdk.asr.CloudAsrConfig;
import com.sinovoice.sdk.asr.FreetalkConfig;
import com.sinovoice.sdk.asr.FreetalkEvent;
import com.sinovoice.sdk.asr.FreetalkResult;
import com.sinovoice.sdk.asr.FreetalkShortAudio;
import com.sinovoice.sdk.asr.FreetalkStream;
import com.sinovoice.sdk.asr.IFreetalkHandler;
import com.sinovoice.sdk.asr.IShortAudioCB;
import com.sinovoice.sdk.asr.ShortAudioConfig;
import com.sinovoice.sdk.asr.Warning;

public class MainActivity extends Activity {

	// 纵向滑动灵敏度，按下录音按钮后向上滑动距离大于改值后松后调用取消识别方法，其余调用开始识别方法
	private static final float MIN_OFFSET_Y = 120l;

	// 日志窗体最大记录的行数，避免溢出问题
	private static final int MAX_LOG_LINES = 5 * 1024;

	private HciSdk sdk;
	private TextView tv_logview;
	private Spinner sp_mode;
	private FreetalkStream ft_stream;
	private FreetalkShortAudio ft_shortaudio;
	private AudioRecorder stream_recorder;
	private AudioRecorder shortaudio_recorder;
	private boolean interim_results;
	private ImageView iv_cancel;
	private int ft_mode;
	private boolean session_busy = false;
	private boolean operating = false;

	static private HciSdk createSdk(Context context) {
		String path = Environment.getExternalStorageDirectory().getAbsolutePath();
		path = path + File.separator + context.getPackageName();

		File file = new File(path);
		if (!file.exists()) {
			file.mkdirs();
		}

		HciSdk sdk = new HciSdk();
		HciSdkConfig cfg = new HciSdkConfig();
		// 平台为应用分配的 appkey
		cfg.setAppkey("aicp_app");
		// 应用对象的密钥 (敏感信息，请勿公开)
		cfg.setSecret("QWxhZGRpbjpvcGVuIHNlc2FtZQ");
		cfg.setSysUrl("https://10.1.18.101:8801/");
		cfg.setCapUrl("http://10.1.18.101:8800/");
		cfg.setDataPath(path);
		cfg.setVerifySSL(false);

		Log.i("sdk-config", cfg.toString());

		sdk.init(cfg, context);
		return sdk;
	}

	private FreetalkConfig freetalkConfig() {
		FreetalkConfig config = new FreetalkConfig();
		config.setProperty("cn_16k_common");
		config.setAudioFormat("pcm_s16le_16k");
		config.setMode(ft_mode);
		config.setAddPunc(true);
		config.setInterimResults(interim_results);
		config.setSlice(200);
		config.setTimeout(10000);
		Log.w("config", config.toString());
		return config;
	}

	private ShortAudioConfig shortAudioConfig() {
		ShortAudioConfig config = new ShortAudioConfig();
		config.setProperty("cn_16k_common");
		config.setAudioFormat("pcm_s16le_16k");
		config.setMode(ft_mode);
		config.setAddPunc(true);
		config.setTimeout(10000);
		return config;
	}

	private final IFreetalkHandler handler = new IFreetalkHandler() {
		@Override
		public void onStart(FreetalkStream s, int code, Warning[] warnings) {
			if (code == 0) {
				printLog("FreetalkStream 识别会话启动成功");
				stream_recorder.start(); // 启动录音机以提供音频数据
			} else {
				printLog("FreetalkStream 识别会话启动失败, code = " + code + "\n");
				session_busy = false;
			}
		}

		@Override
		public void onEnd(FreetalkStream s, int reason) {
			printLog("FreetalkStream 识别会话结束, reason = " + reason + "\n");
			session_busy = false;
		}

		@Override
		public void onError(FreetalkStream s, int code) {
			printLog("FreetalkStream 识别失败，code = " + code);
		}

		@Override
		public void onEvent(FreetalkStream s, FreetalkEvent event) {
			// event 仅可在本回调内使用，如果需要缓存 event，请调用 event.clone()
			printLog("FreetalkStream 语音事件，event = " + event.toString());
		}

		@Override
		public void onResult(FreetalkStream s, FreetalkResult sentence) {
			// sentence 仅可在本回调内使用，如果需要缓存 sentence，请调用 sentence.clone()
			printLog("FreetalkStream 识别结果，sentence = " + sentence.toString());
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		sdk = createSdk(this);

		ft_stream = new FreetalkStream(sdk, new CloudAsrConfig());
		ft_shortaudio = new FreetalkShortAudio(sdk, new CloudAsrConfig());

		sdk.waitForClosed(new IVoidCB() {
			@Override
			public void run() {
				printLog("SDK 已关闭");
				sdk.dispose();
			}
		});
		ft_stream.waitForClosed(new IVoidCB() {
			@Override
			public void run() {
				printLog("FreetalkStream 已关闭");
				ft_stream.dispose();
			}
		});
		ft_shortaudio.waitForClosed(new IVoidCB() {
			@Override
			public void run() {
				printLog("FreetalkShortAudio 已关闭");
				ft_shortaudio.dispose();
			}
		});

		stream_recorder = new AudioRecorder("pcm_s16le_16k", 200, 1000);
		shortaudio_recorder = new AudioRecorder("pcm_s16le_16k", 200, 15000);

		initView();
		initEvents();
	}

	private void initView() {
		tv_logview = (TextView) findViewById(R.id.tv_logview);
		iv_cancel = (ImageView) findViewById(R.id.iv_cancel);

		sp_mode = (Spinner) findViewById(R.id.sp_mode);
	}

	// 录音按钮按下
	private void onRecordButtonDown() {
		// 选项序号与识别模式的取值一致
		ft_mode = sp_mode.getSelectedItemPosition();

		if (ft_mode == ShortAudioConfig.SHORT_AUDIO_MODE) {
			shortaudio_recorder.start();
		} else {
			FreetalkConfig config = freetalkConfig();
			ft_stream.start(config, stream_recorder.audioSource(), handler, true);
		}
	}

	// 录音按钮抬起
	private void onRecordButtonUp(boolean cancel) {
		if (ft_mode == ShortAudioConfig.SHORT_AUDIO_MODE) {
			shortaudio_recorder.stop(cancel);
			int timelen = shortaudio_recorder.bufferTimeLen();
			ByteBuffer audio_data = shortaudio_recorder.readAll();
			ShortAudioConfig config = shortAudioConfig();
			printLog("音频数据长度: " + audio_data.limit());
			printLog("音频数据时长: " + timelen);
			ft_shortaudio.recognize(config, audio_data, new IShortAudioCB() {
				@Override
				public void run(FreetalkShortAudio s, int code, FreetalkResult res, Warning[] warnings) {
					if (code != 0) {
						printLog("一句话识别失败，code = " + code + "\n");
					} else {
						printLog("一句话识别成功，result = " + res.toString() + "\n");
					}
				}
			});
		} else {
			stream_recorder.stop(cancel);
		}
	}

	private void initEvents() {
		Button btn;

		// 录音识别按钮
		btn = (Button) findViewById(R.id.bt_record);
		btn.setOnTouchListener(new OnTouchListener() {
			private float downY;

			@Override
			public boolean onTouch(View v, MotionEvent event) {
				switch (event.getAction()) {
				case MotionEvent.ACTION_DOWN:
					iv_cancel.setVisibility(View.INVISIBLE);
					if (session_busy || shortaudio_recorder.getState() != AudioRecorder.IDLE
							|| stream_recorder.getState() != AudioRecorder.IDLE) {
						// 会话忙碌中或者录音机还在工作，不允许操作
						return true;
					}
					operating = true;
					downY = event.getY();  // 记录按下时的纵坐标，用于计算纵向偏移
					onRecordButtonDown();
					break;
				case MotionEvent.ACTION_MOVE: // 实时计算纵向偏移量，根据条件决定是否显示取消提示窗体
					if (!operating) {
						return true;
					}
					final boolean visib = downY - event.getY() > MIN_OFFSET_Y;
					if (visib && iv_cancel.getVisibility() != View.VISIBLE) {
						iv_cancel.setVisibility(View.VISIBLE);
					} else if (!visib && iv_cancel.getVisibility() == View.VISIBLE) {
						iv_cancel.setVisibility(View.INVISIBLE);
					}
					break;
				case MotionEvent.ACTION_CANCEL:
				case MotionEvent.ACTION_UP: // 抬手时，根据纵坐标偏移，决定开始识别还是取消识别
					iv_cancel.setVisibility(View.INVISIBLE);
					if (!operating) {
						return true;
					}
					operating = false;
					boolean cancel = downY - event.getY() > MIN_OFFSET_Y;
					onRecordButtonUp(cancel);
				}
				return false;
			}
		});

		// 清屏按钮
		btn = (Button) findViewById(R.id.bt_clear);
		btn.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						tv_logview.setText("");
					}
				});
			}
		});

		btn = (Button) findViewById(R.id.bt_close);
		btn.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View arg0) {
				sdk.close();
				findViewById(R.id.bt_record).setEnabled(false);
			}
		});
		CheckBox cb;
		cb = (CheckBox) findViewById(R.id.ck_interim_result);
		cb.setOnCheckedChangeListener(new OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton cb, boolean checked) {
				interim_results = checked;
			}
		});
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_settings) {
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private final Runnable scrollLog = new Runnable() {
		@Override
		public void run() {
			((ScrollView) tv_logview.getParent()).fullScroll(ScrollView.FOCUS_DOWN);
		}
	};

	private void printLog(final String detail) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				// 日志输出同时记录到日志文件中
				if (tv_logview == null) {
					return;
				}

				// 如日志行数大于上限，则清空日志内容
				if (tv_logview.getLineCount() > MAX_LOG_LINES) {
					tv_logview.setText("");
				}

				// 在当前基础上追加日志
				tv_logview.append(detail + "\n");

				// 二次刷新确保父控件向下滚动能到达底部,解决一次出现多行日志时滚动不到底部的问题
				tv_logview.post(scrollLog);
			}
		});
	}
}
