package com.example.poetry.media;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.example.poetry.R;

/**
 * 腾讯云语音合成提供的在线发音人。
 * <p>
 * 这些音色不是 App 内置的，是腾讯云 {@code tts.tencentcloudapi.com} 的在线服务。
 * 名字用腾讯官方音色名（智瑜、智云……），不在 App 里另起名——它们是云端服务侧的
 * 固定 id，改名反而让用户对不上号。音色 id 见
 * <a href="https://cloud.tencent.com/document/product/1073/92668">官方音色列表</a>。
 * <p>
 * {@code voiceType} 即腾讯的「音色 ID」（如 {@code 101001}），原样传给
 * {@link QCloudTts}。{@code 101xxx} 是基础/精品音色，{@code 501xxx} 是大模型音色
 * （更自然、单价更高），用一个 {@code premium} 标记区分，界面上可以加角标提示。
 */
public final class QCloudRoles {

    /** 一个腾讯云在线发音人。 */
    public static final class Role {

        /** 腾讯音色 ID，原样传给 {@code TtsController.setOnlineVoiceType()}。 */
        public final int voiceType;
        @StringRes
        public final int nameRes;
        @StringRes
        public final int descRes;
        public final boolean male;
        /** 大模型音色（更自然、更贵），界面上可加角标。 */
        public final boolean premium;

        Role(int voiceType, @StringRes int nameRes, @StringRes int descRes,
             boolean male, boolean premium) {
            this.voiceType = voiceType;
            this.nameRes = nameRes;
            this.descRes = descRes;
            this.male = male;
            this.premium = premium;
        }
    }

    /** 挑出来交给界面、且适合读诗的在线发音人，按女/男、精品/大模型排列。 */
    public static final Role[] ALL = {
            new Role(101001, R.string.voice_qcloud_zhiyu_name, R.string.voice_qcloud_zhiyu_desc, false, false),
            new Role(101004, R.string.voice_qcloud_zhiyun_name, R.string.voice_qcloud_zhiyun_desc, true, false),
            new Role(101011, R.string.voice_qcloud_zhiyan_name, R.string.voice_qcloud_zhiyan_desc, false, false),
            new Role(101013, R.string.voice_qcloud_zhihui_name, R.string.voice_qcloud_zhihui_desc, true, false),
            new Role(101015, R.string.voice_qcloud_zhimeng_name, R.string.voice_qcloud_zhimeng_desc, true, false),
            new Role(101016, R.string.voice_qcloud_zhitian_name, R.string.voice_qcloud_zhitian_desc, false, false),
            new Role(101019, R.string.voice_qcloud_zhitong_name, R.string.voice_qcloud_zhitong_desc, false, false),
            new Role(101021, R.string.voice_qcloud_zhirui_name, R.string.voice_qcloud_zhirui_desc, true, false),
            new Role(101026, R.string.voice_qcloud_zhixi_name, R.string.voice_qcloud_zhixi_desc, false, false),
            new Role(101030, R.string.voice_qcloud_zhike_name, R.string.voice_qcloud_zhike_desc, true, false),
            //new Role(501000, R.string.voice_qcloud_zhibin_name, R.string.voice_qcloud_zhibin_desc, true, true),
            //new Role(501002, R.string.voice_qcloud_zhiju_name, R.string.voice_qcloud_zhiju_desc, false, true),
    };

    private QCloudRoles() {
    }

    /** 音色 id：{@code qcloud-<voicetype>}。 */
    @NonNull
    public static String voiceId(int voiceType) {
        return "qcloud-" + voiceType;
    }

    /** 是不是本 App 认识的腾讯云音色 id。 */
    public static boolean isCloudId(@Nullable String id) {
        return id != null && id.startsWith("qcloud-");
    }

    /** 拆出音色 id 里的腾讯音色 ID；不是云端 id 或格式不对返回 null。 */
    @Nullable
    public static Integer voiceTypeOf(@Nullable String id) {
        if (!isCloudId(id)) {
            return null;
        }
        try {
            return Integer.parseInt(id.substring("qcloud-".length()));
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }

    /** 按腾讯音色 ID 找角色；没有返回 null。 */
    @Nullable
    public static Role forVoiceType(int voiceType) {
        for (Role role : ALL) {
            if (role.voiceType == voiceType) {
                return role;
            }
        }
        return null;
    }
}
