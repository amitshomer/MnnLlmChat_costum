package com.alibaba.mnnllm.android.chat

const val DEFAULT_SANA_PROMPT: String =
    "Convert to a Ghibli-style illustration: soft contrast, warm tones, slight linework, keep the scene consistent."

const val DEFAULT_VISUAL_PROMPT: String =
    "You must output EXACTLY one line.\n" +
    "Choose one:\n" +
    "CAPTURE\n" +
    "IMPROVE: <short explanation>\n" +
    "Give me very short recommendation how to make image composition better just if needed.\n" +
    "Only output IMPROVE if the composition problem is obvious and severe.\n" +
    "Otherwise output CAPTURE.\n" +
    "When in doubt, always choose CAPTURE.\n"
