package com.temi.rhythmgame;

import android.content.Context;

final class PatrolFailureStore {
    private static final String PREFS = "patrol_failure";
    private static final String LAST_ERROR = "last_error";

    private PatrolFailureStore() { }

    static void save(Context context, String location, String session, String stage, String detail) {
        String value = "이전 순찰 확인 실패: time=" + System.currentTimeMillis()
                + ", location=" + location + ", session=" + session
                + ", stage=" + stage + ", detail=" + detail;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(LAST_ERROR, value).apply();
    }

    static String lastError(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_ERROR, null);
    }
}
