package com.ai.face.verify;

import static com.ai.face.FaceAIConfig.CACHE_BASE_FACE_DIR;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.ai.face.R;
import com.ai.face.base.baseImage.FaceAIUtils;
import com.ai.face.base.view.CameraXFragment;
import com.ai.face.base.view.FaceCoverView;
import com.ai.face.faceVerify.verify.FaceProcessBuilder;
import com.ai.face.faceVerify.verify.FaceVerifyUtils;
import com.ai.face.faceVerify.verify.ProcessCallBack;
import com.ai.face.faceVerify.verify.VerifyStatus.*;
import com.ai.face.faceVerify.verify.VerifyUtils;
import com.ai.face.faceVerify.verify.liveness.LivenessDetectionMode;
import com.ai.face.faceVerify.verify.liveness.LivenessType;
import com.ai.face.utils.VoicePlayer;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;

import org.jetbrains.annotations.NotNull;

/**
 * 1：1 的人脸识别 + 动作活体检测 SDK 接入演示Demo 代码
 * <p>
 * 人脸图要求：
 * 1.尽量使用较高配置设备和摄像头，光线不好带上补光灯
 * 2.录入高质量的人脸图，人脸清晰，背景纯色（证件照输入目前优化中）
 * 3.光线环境好，检测的人脸无遮挡，无浓妆或佩戴墨镜口罩帽子等
 * 4.人脸照片要求300*300 以上 裁剪好的仅含人脸的正方形照片，背景纯色
 * <p>
 * 返回给uniApp 插件的Code 含义
 * 0: 校验成功
 * 1：活体校验失败
 * 2: 动作活体超时
 * 3: 用户退出识别
 * 4；
 */
public class FaceVerificationActivity extends AppCompatActivity {
    public static final String USER_FACE_ID_KEY = "USER_FACE_ID_KEY";   //1:1 face verify ID KEY

    private TextView tipsTextView, secondTipsTextView, scoreText;
    private FaceCoverView faceCoverView;
    private ImageView baseFaceImageView;
    private final FaceVerifyUtils faceVerifyUtils = new FaceVerifyUtils();
    private CameraXFragment cameraXFragment;
    //静默活体检测要求 RGB 镜头 720p， 固定 30 帧，无拖影，RGB 镜头建议是宽动态
    private final float silentLivenessPassScore = 0.85f; //静默活体分数通过的阈值

    private String faceID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_face_verification);//建议背景白色可以补充光照不足
        setTitle("1:1 face verify");
        scoreText = findViewById(R.id.silent_Score);
        tipsTextView = findViewById(R.id.tips_view);
        secondTipsTextView = findViewById(R.id.second_tips_view);
        faceCoverView = findViewById(R.id.face_cover);
        baseFaceImageView = findViewById(R.id.base_face);

        findViewById(R.id.back).setOnClickListener(v -> {
            onBackPressed();
        });

        SharedPreferences sharedPref = getSharedPreferences("FaceAISDK", Context.MODE_PRIVATE);

        int cameraLensFacing = sharedPref.getInt("cameraFlag", 0);
        int degree = sharedPref.getInt("cameraDegree", 0);

        /*
         * 1. Camera 的初始化。
         * 第一个参数0/1 指定前后摄像头；
         * 第二个参数linearZoom [0.001f,1.0f] 指定焦距，参考{@link CameraControl#setLinearZoom(float)}
         * 焦距拉远一点，人才会靠近屏幕，才会减轻杂乱背景的影响。定制设备的摄像头自行调教此参数
         *
         * 第三个参数是摄像头旋转角度 {@Link Surface.ROTATION_0}
         */
        cameraXFragment = CameraXFragment.newInstance(cameraLensFacing, 0.001f, degree);

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_camerax, cameraXFragment).commit();

        initFaceVerifyBaseBitmap();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        Intent intent = new Intent().putExtra("code", 0).putExtra("msg", "用户取消");
        setResult(RESULT_OK, intent);
        finish();
    }

    /**
     * 初始化人脸识别底图
     */
    private void initFaceVerifyBaseBitmap() {
        //1:1 人脸对比，摄像头实时采集的人脸和预留的人脸底片对比。（动作活体人脸检测完成后开始1:1比对）
        faceID = getIntent().getStringExtra(USER_FACE_ID_KEY);

        //2.先去Path 路径读取有没有faceID 对应的处理好的人脸，如果没有从网络其他地方同步图片过来并进行合规处理
        String faceFilePath = CACHE_BASE_FACE_DIR + faceID;
        Bitmap baseBitmap = BitmapFactory.decodeFile(faceFilePath);

        //如果本地已经有了合规处理好的人脸图
        if (baseBitmap != null) {
            //3.初始化引擎，各种参数配置
            initFaceVerificationParam(baseBitmap);
        } else {

            Toast.makeText(this, faceID + getString(R.string.no_base_face), Toast.LENGTH_SHORT).show();


//            //模拟从网络等地方获取对应的人脸图，Demo 简化从Asset 目录读取
//            Bitmap remoteBitmap = VerifyUtils.getBitmapFromAssert(this, "0a_模拟证件照.jpeg");
//            if (remoteBitmap == null) {
//                Toast.makeText(getBaseContext(), R.string.add_a_face_image, Toast.LENGTH_LONG).show();
//                tipsTextView.setText(R.string.add_a_face_image);
//                return;
//            }
//
//            //其他地方同步过来的人脸可能是不规范的没有经过校准的人脸图（证件照，多人脸，过小等）。disposeBaseFaceImage处理
//            FaceAIUtils.Companion.getInstance(getApplication())
//                    .disposeBaseFaceImage(remoteBitmap, faceFilePath, new FaceAIUtils.Callback() {
//                        //处理优化人脸成功完成去初始化引擎
//                        @Override
//                        public void onSuccess(@NonNull Bitmap disposedBitmap) {
//                            initFaceVerificationParam(disposedBitmap);
//                        }
//
//                        //底片处理异常的信息回调
//                        @Override
//                        public void onFailed(@NotNull String msg, int errorCode) {
//                            Toast.makeText(getBaseContext(), msg, Toast.LENGTH_LONG).show();
//
//                            Intent intent = new Intent().putExtra("code",0 ).putExtra("msg", "用户取消");
//                            setResult(RESULT_OK, intent);
//                            finish();
//                        }
//                    });
        }
    }


    /**
     * 初始化认证引擎
     * <p>
     * 活体检测的使用需要你发送邮件申请，简要描述App名称，包名 签名SHA1和功能简介到 FaceAISDK.Service@gmail.com
     *
     * @param baseBitmap 1:1 人脸识别对比的底片，如果仅仅需要活体检测，可以把App logo Bitmap 当参数传入并忽略对比结果
     */
    private void initFaceVerificationParam(Bitmap baseBitmap) {

        FaceProcessBuilder faceProcessBuilder = new FaceProcessBuilder.Builder(this)
                .setThreshold(0.85f)                    //阈值设置，范围限 [0.8,0.95] 识别可信度，也是识别灵敏度
                .setBaseBitmap(baseBitmap)              //1:1 人脸识别对比的底片，仅仅需要SDK活体检测可以忽略比对结果
                .setLivenessType(LivenessType.SILENT_MOTION)  //活体检测可以有静默活体，动作活体或者组合也可以不需要活体NONE
                .setLivenessDetectionMode(LivenessDetectionMode.FAST) //硬件配置低用FAST动作活体模式，否则用精确模式
                .setSilentLivenessThreshold(silentLivenessPassScore)  //静默活体阈值 [0.88,0.98]
                .setMotionLivenessStepSize(1)           //随机动作活体的步骤个数[1-2]，SILENT_MOTION和MOTION 才有效
                .setExceptMotionLivelessType(ALIVE_DETECT_TYPE_ENUM.SMILE) //动作活体去除微笑等某一种
                .setVerifyTimeOut(16)                   //活体检测支持设置超时时间 [9,22] 秒
                .setProcessCallBack(new ProcessCallBack() {
                    /**
                     * 1:1 人脸识别 活体检测 对比结束
                     *
                     * @param isMatched   true匹配成功（大于setThreshold）； false 与底片不是同一人
                     * @param similarity  与底片匹配的相似度值
                     * @param vipBitmap   识别完成的时候人脸实时图，仅授权用户会返回。可以拿这张图存档案和你的服务器再次严格匹配
                     */
                    @Override
                    public void onVerifyMatched(boolean isMatched, float similarity, float silentLivenessScore, Bitmap vipBitmap) {
                        showVerifyResult(isMatched, similarity, silentLivenessScore);
                    }

                    //人脸识别，活体检测过程中的各种提示
                    @Override
                    public void onProcessTips(int i) {
                        showFaceVerifyTips(i);
                    }

                    //动作活体检测时间限制倒计时百分比
                    @Override
                    public void onTimeCountDown(float percent) {
                        faceCoverView.startCountDown(percent);
                    }

                    /**
                     * 发送严重错误，会中断业务流程
                     *
                     */
                    @Override
                    public void onFailed(int code, String message) {
                        Toast.makeText(getBaseContext(), "onFailed错误!：" + message, Toast.LENGTH_LONG).show();
                    }

                }).create();


        faceVerifyUtils.setDetectorParams(faceProcessBuilder);

        cameraXFragment.setOnAnalyzerListener(imageProxy -> {
            //防止在识别过程中关闭页面导致Crash
            if (!isDestroyed() && !isFinishing()) {
                //2.第二个参数是指圆形人脸框到屏幕边距，可加快裁剪图像和指定识别区域，设太大会裁剪掉人脸区域
                faceVerifyUtils.goVerifyWithImageProxy(imageProxy, faceCoverView.getMargin());
                //自定义管理相机可以使用 goVerifyWithBitmap
            }
        });


        Glide.with(getBaseContext())
                .load(baseBitmap)
                .transform(new RoundedCorners(12))
                .into(baseFaceImageView);
    }

    /**
     * 检测1:1 人脸识别是否通过
     * <p>
     * 动作活体要有动作配合，必须先动作匹配通过再1：1 匹配
     * 静默活体不需要人配合，如果不需要静默活体检测，分数直接会被赋值 1.0
     */
    private void showVerifyResult(boolean isVerifyMatched, float similarity, float silentLivenessScore) {
        runOnUiThread(() -> {

            if (VerifyUtils.Ii丨1(getBaseContext())) {
                scoreText.setText("SilentLivenessScore:" + silentLivenessScore);
            }

            //1.静默活体分数判断
            if (silentLivenessScore < silentLivenessPassScore) {
                tipsTextView.setText(R.string.silent_anti_spoofing_error);
                new AlertDialog.Builder(FaceVerificationActivity.this)
                        .setMessage(R.string.silent_anti_spoofing_error)
                        .setCancelable(false)
                        .setPositiveButton(R.string.confirm, (dialogInterface, i) -> {
                            retryTime++;
                            //建议控制重试次数，一般2次没成功基本不用重试了，设备配置太低或环境因素
                            if (retryTime > 1) {
                                Intent intent = new Intent().putExtra("code", 2).putExtra("msg", "活体分数过低");
                                setResult(RESULT_OK, intent);
                                finish();
                            } else {
                                faceVerifyUtils.retryVerify();
                            }

                        })
                        .show();
            } else if (isVerifyMatched) {
                if (VerifyUtils.Ii丨1(getBaseContext())) {
                    tipsTextView.setText("Successful,similarity= " + similarity);
                } else {
                    //2.和底片同一人
                    tipsTextView.setText(R.string.face_verify_success);
                    Toast.makeText(getBaseContext(), R.string.face_verify_success, Toast.LENGTH_LONG).show();
                }

                VoicePlayer.getInstance().addPayList(R.raw.verify_success);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Intent intent = new Intent().putExtra("code", 1).putExtra("msg", "人脸识别成功");
                    setResult(RESULT_OK, intent);
                    finish();
                }, 2000);
            } else {
                //3.和底片不是同一个人
                if (VerifyUtils.Ii丨1(getBaseContext())) {
                    tipsTextView.setText("Failed ！ similarity=" + similarity);
                } else {
                    //2.和底片同一人
                    tipsTextView.setText(R.string.face_verify_failed);
                    Toast.makeText(getBaseContext(), R.string.face_verify_failed, Toast.LENGTH_LONG).show();
                }

                VoicePlayer.getInstance().addPayList(R.raw.verify_failed);
                new AlertDialog.Builder(FaceVerificationActivity.this)
                        .setTitle(R.string.face_verify_failed)
                        .setMessage(R.string.face_verify_failed)
                        .setCancelable(false)
                        .setPositiveButton(R.string.confirm, (dialogInterface, i) -> {
                            //数据是使用Intent返回
                            Intent intent = new Intent().putExtra("code", 4).putExtra("msg", "人脸识别低于阈值");
                            setResult(RESULT_OK, intent);
                            finish();
                        })
                        .setNegativeButton(R.string.retry, (dialog, which) -> {
                            faceVerifyUtils.retryVerify();
                        })
                        .show();
            }
        });
    }


    /**
     * 根据业务和设计师UI交互修改你的 UI，Demo 仅供参考
     * <p>
     * 添加声音提示和动画提示定制也在这里根据返回码进行定制
     */
    int retryTime = 0;

    private void showFaceVerifyTips(int actionCode) {
        if (!isDestroyed() && !isFinishing()) {
            runOnUiThread(() -> {
                switch (actionCode) {
                    case VERIFY_DETECT_TIPS_ENUM.ACTION_PROCESS:
                        tipsTextView.setText(R.string.face_verifying);
                        break;

                    case VERIFY_DETECT_TIPS_ENUM.ACTION_NO_FACE:
                        tipsTextView.setText(R.string.no_face_detected_tips);
                        break;

                    case VERIFY_DETECT_TIPS_ENUM.ACTION_FAILED:
                        tipsTextView.setText(R.string.motion_liveness_detection_failed);
                        break;

                    case VERIFY_DETECT_TIPS_ENUM.ACTION_OK:
                        VoicePlayer.getInstance().play(R.raw.face_camera);
                        tipsTextView.setText(R.string.keep_face_visible);
                        break;

                    case ALIVE_DETECT_TYPE_ENUM.OPEN_MOUSE:
                        VoicePlayer.getInstance().play(R.raw.open_mouse);
                        tipsTextView.setText(R.string.repeat_open_close_mouse);
                        break;

                    case ALIVE_DETECT_TYPE_ENUM.SMILE: {
                        tipsTextView.setText(R.string.motion_smile);
                        VoicePlayer.getInstance().play(R.raw.smile);
                    }
                    break;

                    case ALIVE_DETECT_TYPE_ENUM.BLINK: {
                        VoicePlayer.getInstance().play(R.raw.blink);
                        tipsTextView.setText(R.string.motion_blink_eye);
                    }
                    break;

                    case ALIVE_DETECT_TYPE_ENUM.SHAKE_HEAD:
                        VoicePlayer.getInstance().play(R.raw.shake_head);
                        tipsTextView.setText(R.string.motion_shake_head);
                        break;

                    case ALIVE_DETECT_TYPE_ENUM.NOD_HEAD:
                        VoicePlayer.getInstance().play(R.raw.nod_head);
                        tipsTextView.setText(R.string.motion_node_head);
                        break;

                    case VERIFY_DETECT_TIPS_ENUM.ACTION_TIME_OUT:
                        new AlertDialog.Builder(this)
                                .setMessage(R.string.motion_liveness_detection_time_out)
                                .setCancelable(false)
                                .setPositiveButton(R.string.retry, (dialogInterface, i) -> {
                                            retryTime++;
                                            //建议控制重试次数，一般2次没成功基本不用重试了，设备配置太低或环境因素
                                            if (retryTime > 1) {

                                                Intent intent = new Intent().putExtra("code", 3).putExtra("msg", "活体检测超时");
                                                setResult(RESULT_OK, intent);
                                                finish();

//                                                //记得按钮名字改一下
//                                                FaceVerificationActivity.this.finish();
                                            } else {
                                                faceVerifyUtils.retryVerify();
                                            }
                                        }
                                ).show();
                        break;

                    case VERIFY_DETECT_TIPS_ENUM.NO_FACE_REPEATEDLY:
                        tipsTextView.setText(R.string.no_face_or_repeat_switch_screen);

                        //处理方式可以参考超时Timeout
                        new AlertDialog.Builder(this)
                                .setMessage(R.string.stop_verify_tips)
                                .setCancelable(false)
                                .setPositiveButton(R.string.confirm, (dialogInterface, i) -> {
                                    //finish();  //是finish 还是retryVerify根据你的业务自己定
                                    faceVerifyUtils.retryVerify();
                                })
                                .show();

                        break;

                    // 单独使用一个textview 提示，防止上一个提示被覆盖。
                    // 也可以自行记住上个状态，FACE_SIZE_FIT 中恢复上一个提示
                    case VERIFY_DETECT_TIPS_ENUM.FACE_TOO_LARGE:
                        secondTipsTextView.setText(R.string.far_away_tips);
                        break;

                    //人脸太小了，靠近一点摄像头
                    case VERIFY_DETECT_TIPS_ENUM.FACE_TOO_SMALL:
                        secondTipsTextView.setText(R.string.come_closer_tips);
                        break;

                    //检测到正常的人脸，尺寸大小OK
                    case VERIFY_DETECT_TIPS_ENUM.FACE_SIZE_FIT:
                        secondTipsTextView.setText("");
                        break;

                }
            });
        }
    }


    /**
     * 资源释放
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        faceVerifyUtils.destroyProcess();
    }


    /**
     * 暂停识别，防止切屏识别，如果你需要退后台不能识别的话
     */
    protected void onPause() {
        super.onPause();
        faceVerifyUtils.pauseProcess();
    }

}

