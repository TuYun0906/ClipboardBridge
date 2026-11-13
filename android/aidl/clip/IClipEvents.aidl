package clip;

import android.os.ParcelFileDescriptor;

oneway interface IClipEvents {
    void onText(String text);
    void onImage(in ParcelFileDescriptor image);
}
