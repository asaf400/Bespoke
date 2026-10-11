package de.robv.android.xposed;

public class XSharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) {}

    public void reload() {}

    public boolean getBoolean(String key, boolean defValue) {
        return defValue;
    }
}
