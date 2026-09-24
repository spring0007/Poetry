package com.example.poetry.media;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.example.poetry.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 随 APK 分发的语音包登记表。
 * <p>
 * 一个「语音包」= 一套 sherpa-onnx 模型（.onnx + 发音词典 + 文本正规化规则），
 * 放在 assets 的某个目录里，装好 App 就能用，不联网也不依赖任何第三方 App。
 * 一个语音包里可以有多个发音人：模型自己按 {@code sid}（speaker id）区分音色，
 * 这里把其中一部分挑出来、配上中文名字交给界面。
 * <p>
 * 眼下只有一个包 {@link #AISHELL3}，15 个发音人。要再内置一套语音库，
 * 往 {@link #ALL} 里加一条 {@link Pack}、把模型文件放进
 * {@code assets/tts/<新目录>/}、再补上 {@code tools/fetch-tts-deps.sh} 的下载步骤即可，
 * {@link SherpaTts} 不用动。
 * <p>
 * <b>发音人是挑过的，不是全量。</b>aishell3 有 174 个人，官方只给匿名 id
 * （{@code SSB0005} 这种），逐个起中文名毫无意义、列出来也没法选。所以这里按
 * 基频（F0）从低到高挑出音域分布均匀、且合成稳定性好的 15 个，逐个起名。
 * 挑人的依据记录在 {@code TTS.md}。
 */
final class VoicePacks {

    /** 一个发音人。 */
    static final class Role {

        /** sherpa-onnx 的 speaker id，原样传给 {@code OfflineTts.generate()}。 */
        final int sid;
        /**
         * 模型 {@code speakers.txt} 里第 {@code sid} 行的官方 id。
         * 加载时逐行核对，见 {@link #verifiedRoles}——它保证「用户选的苍柏」
         * 在换了模型文件之后仍然还是那个声音，而不是碰巧排在第 136 位的另一个人。
         */
        @NonNull
        final String speaker;
        @StringRes
        final int nameRes;
        @StringRes
        final int descRes;
        final boolean male;

        Role(int sid, @NonNull String speaker, @StringRes int nameRes,
             @StringRes int descRes, boolean male) {
            this.sid = sid;
            this.speaker = speaker;
            this.nameRes = nameRes;
            this.descRes = descRes;
            this.male = male;
        }
    }

    /** 一个语音包。 */
    static final class Pack {

        /** 短标识，进音色 id（{@code sherpa-<id>-<sid>}）并存进用户配置，不要改。 */
        @NonNull
        final String id;
        /** assets 下的目录，被 .gitignore 忽略，见 tools/fetch-tts-deps.sh。 */
        @NonNull
        final String assetDir;
        /** 主模型文件名。'.onnx.json' 那种 piper 私有配置不需要——sherpa-onnx 从 onnx 内嵌的 metadata 读采样率。 */
        @NonNull
        final String modelName;
        /** 运行时真正要读的文件，缺一不可；也决定首启要拷多少。 */
        @NonNull
        final String[] files;
        /**
         * 文本正规化 FST，按顺序串联（数字 / 日期 / 电话号码 / 多音字）。
         * 由 {@link SherpaTts} 拼成绝对路径传给引擎。
         */
        @NonNull
        final String[] ruleFsts;
        /** 推理参数，取自模型自带的配置。 */
        final float noiseScale;
        final float noiseW;
        final float lengthScale;
        /** 模型采样率，仅在 AudioTrack 拒绝报告采样率时兜底。 */
        final int fallbackSampleRate;
        /** 挑出来交给界面的发音人，按基频从低到高。 */
        @NonNull
        final Role[] roles;
        /** 默认发音人的 sid。用户没选过、或存着的 id 已经不存在时用它。 */
        final int defaultSid;

        Pack(@NonNull String id, @NonNull String assetDir, @NonNull String modelName,
             @NonNull String[] files, @NonNull String[] ruleFsts,
             float noiseScale, float noiseW, float lengthScale, int fallbackSampleRate,
             @NonNull Role[] roles, int defaultSid) {
            this.id = id;
            this.assetDir = assetDir;
            this.modelName = modelName;
            this.files = files;
            this.ruleFsts = ruleFsts;
            this.noiseScale = noiseScale;
            this.noiseW = noiseW;
            this.lengthScale = lengthScale;
            this.fallbackSampleRate = fallbackSampleRate;
            this.roles = roles;
            this.defaultSid = defaultSid;
        }
    }

    /** 音色 id 拆开之后的样子。 */
    static final class Address {

        @NonNull
        final Pack pack;
        final int sid;

        Address(@NonNull Pack pack, int sid) {
            this.pack = pack;
            this.sid = sid;
        }
    }

    /**
     * 中英混读的通用中文女声，174 个发音人。
     * <p>
     * 模型来自 {@code vits-icefall-zh-aishell3}。它比原先的 piper 小雅模型大 10 MB，
     * 换来的是「一台手机上有 15 种声音」。{@code rule.far}（180 MB）刻意不带——
     * 它是全量 AISHELL-3 录音的检索库，只有训练/评测用得上，官方推理命令也没引用它。
     */
    static final Pack AISHELL3 = new Pack(
            "aishell3",
            "tts/aishell3",
            "model.onnx",
            new String[]{
                    "model.onnx",
                    "tokens.txt",
                    "lexicon.txt",
                    "speakers.txt",
                    "phone.fst",
                    "date.fst",
                    "number.fst",
                    "new_heteronym.fst",
            },
            new String[]{"phone.fst", "date.fst", "number.fst", "new_heteronym.fst"},
            0.667f, 0.8f, 1.0f, 22050,
            new Role[]{
                    // 男声，基频由低到高（119.5 → 162.2 Hz）
                    new Role(136, "SSB1383", R.string.voice_role_cangbai_name,
                            R.string.voice_role_cangbai_desc, true),
                    new Role(163, "SSB1831", R.string.voice_role_zimo_name,
                            R.string.voice_role_zimo_desc, true),
                    new Role(76, "SSB0631", R.string.voice_role_hanshan_name,
                            R.string.voice_role_hanshan_desc, true),
                    new Role(25, "SSB0273", R.string.voice_role_songfeng_name,
                            R.string.voice_role_songfeng_desc, true),
                    new Role(75, "SSB0629", R.string.voice_role_yuanshan_name,
                            R.string.voice_role_yuanshan_desc, true),
                    new Role(65, "SSB0590", R.string.voice_role_heming_name,
                            R.string.voice_role_heming_desc, true),
                    // 女声，基频由低到高（197.7 → 275.6 Hz）
                    new Role(38, "SSB0354", R.string.voice_role_suxin_name,
                            R.string.voice_role_suxin_desc, false),
                    new Role(144, "SSB1555", R.string.voice_role_shuying_name,
                            R.string.voice_role_shuying_desc, false),
                    new Role(88, "SSB0748", R.string.voice_role_tingyu_name,
                            R.string.voice_role_tingyu_desc, false),
                    new Role(62, "SSB0570", R.string.voice_role_ruolan_name,
                            R.string.voice_role_ruolan_desc, false),
                    new Role(90, "SSB0758", R.string.voice_role_wanqing_name,
                            R.string.voice_role_wanqing_desc, false),
                    new Role(120, "SSB1108", R.string.voice_role_caiwei_name,
                            R.string.voice_role_caiwei_desc, false),
                    new Role(0, "SSB0005", R.string.voice_role_xiaoya_name,
                            R.string.voice_role_xiaoya_desc, false),
                    new Role(17, "SSB0149", R.string.voice_role_mingyue_name,
                            R.string.voice_role_mingyue_desc, false),
                    new Role(33, "SSB0323", R.string.voice_role_lingxi_name,
                            R.string.voice_role_lingxi_desc, false),
            },
            /* defaultSid= */ 0);

    /** 要加载的全部语音包，按显示顺序。 */
    static final Pack[] ALL = {AISHELL3};

    private VoicePacks() {
    }

    /** 音色 id：{@code sherpa-<包>-<sid>}。 */
    @NonNull
    static String voiceId(@NonNull Pack pack, int sid) {
        return "sherpa-" + pack.id + "-" + sid;
    }

    /**
     * 逐行核对 {@code speakers.txt}，丢掉对不上的发音人。
     * <p>
     * 这一层看着多余，实则不然：{@code sid} 只是数组下标，模型文件一换、官方把
     * 说话人顺序一调，「苍柏」就会一声不响地变成别人。核对之后，对不上的发音人
     * 直接不列出来——用户看到少了几个音色，比选了个陌生声音要好。
     * <p>
     * 整份 {@code speakers.txt} 都比 {@code engine.numSpeakers()} 短或长时，
     * 认为这个包本身对不上，返回空表。
     */
    @NonNull
    static List<Role> verifiedRoles(@NonNull Pack pack, @NonNull List<String> speakers) {
        List<Role> result = new ArrayList<>(pack.roles.length);
        for (Role role : pack.roles) {
            if (role.sid < 0 || role.sid >= speakers.size()) {
                continue;
            }
            if (role.speaker.equals(speakers.get(role.sid).trim())) {
                result.add(role);
            }
        }
        return result;
    }

    /**
     * 拆音色 id，返回包与 sid；不是本 App 认识的形状就返回 null。
     * <p>
     * 老版本存下的 {@code sherpa-xiao-ya} 也会走到 null 这条路——那个音色已经不存在了，
     * 上层按「没匹配上就用默认」处理（见 {@code VoiceSettingsActivity.reloadVoices()}），
     * 用户不需要理解这个落差。
     */
    @Nullable
    static Address parse(@Nullable String voiceId) {
        if (voiceId == null || !voiceId.startsWith("sherpa-")) {
            return null;
        }
        String rest = voiceId.substring("sherpa-".length());
        int dash = rest.lastIndexOf('-');
        if (dash <= 0 || dash == rest.length() - 1) {
            return null;
        }
        String packId = rest.substring(0, dash);
        int sid;
        try {
            sid = Integer.parseInt(rest.substring(dash + 1));
        } catch (NumberFormatException notAnIndex) {
            return null;
        }
        for (Pack pack : ALL) {
            if (pack.id.equals(packId)) {
                return new Address(pack, sid);
            }
        }
        return null;
    }

    /** 找出 {@code pack} 里 sid 为 {@code sid} 的发音人；没有就返回 null。 */
    @Nullable
    static Role roleForSid(@NonNull Pack pack, int sid) {
        for (Role role : pack.roles) {
            if (role.sid == sid) {
                return role;
            }
        }
        return null;
    }
}
