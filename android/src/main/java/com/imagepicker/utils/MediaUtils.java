package com.imagepicker.utils;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.facebook.react.bridge.ReadableMap;
import com.imagepicker.ImagePickerModule;
import com.imagepicker.ResponseHelper;
import com.imagepicker.media.ImageConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

import static com.imagepicker.ImagePickerModule.REQUEST_LAUNCH_IMAGE_CAPTURE;

/**
 * Created by rusfearuth on 15.03.17.
 */

public class MediaUtils
{
    private static final String TAG = "MediaUtils";
    public static @Nullable File createNewFile(@NonNull final Context reactContext,
                                               @NonNull final ReadableMap options,
                                               @NonNull final boolean forceLocal)
    {
        final String filename = new StringBuilder("image-")
                .append(UUID.randomUUID().toString())
                .append(".jpg")
                .toString();

        final File path = ReadableMapUtils.hasAndNotNullReadableMap(options, "storageOptions")
                && ReadableMapUtils.hasAndNotEmptyString(options.getMap("storageOptions"), "path")
                ? new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), options.getMap("storageOptions").getString("path"))
                : (!forceLocal ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                : reactContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES));

        File result = new File(path, filename);

        try
        {
            path.mkdirs();
            result.createNewFile();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            result = null;
        }

        return result;
    }

    /**
     * Create a resized image to fulfill the maxWidth/maxHeight, quality and rotation values
     *
     * @param context
     * @param options
     * @param imageConfig
     * @param initialWidth
     * @param initialHeight
     * @return updated ImageConfig
     */
    public static @NonNull ImageConfig getResizedImage(@NonNull final Context context,
                                                       @NonNull final ReadableMap options,
                                                       @NonNull final ImageConfig imageConfig,
                                                       int initialWidth,
                                                       int initialHeight,
                                                       final int requestCode)
    {
        BitmapFactory.Options imageOptions = new BitmapFactory.Options();
        imageOptions.inScaled = false;
        imageOptions.inSampleSize = 1;

        if (imageConfig.maxWidth != 0 || imageConfig.maxHeight != 0) {
            while ((imageConfig.maxWidth == 0 || initialWidth > 2 * imageConfig.maxWidth) &&
                    (imageConfig.maxHeight == 0 || initialHeight > 2 * imageConfig.maxHeight)) {
                imageOptions.inSampleSize *= 2;
                initialHeight /= 2;
                initialWidth /= 2;
            }
        }

        Bitmap photo = BitmapFactory.decodeFile(imageConfig.original.getAbsolutePath(), imageOptions);

        if (photo == null)
        {
            return null;
        }

        ImageConfig result = imageConfig;

        Bitmap scaledPhoto = null;
        if (imageConfig.maxWidth == 0 || imageConfig.maxWidth > initialWidth)
        {
            result = result.withMaxWidth(initialWidth);
        }
        if (imageConfig.maxHeight == 0 || imageConfig.maxWidth > initialHeight)
        {
            result = result.withMaxHeight(initialHeight);
        }

        double widthRatio = (double) result.maxWidth / initialWidth;
        double heightRatio = (double) result.maxHeight / initialHeight;

        double ratio = (widthRatio < heightRatio)
                ? widthRatio
                : heightRatio;

        Matrix matrix = new Matrix();
        matrix.postRotate(result.rotation);
        matrix.postScale((float) ratio, (float) ratio);

        ExifInterface exif;
        try
        {
            exif = new ExifInterface(result.original.getAbsolutePath());

            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0);

            switch (orientation)
            {
                case 6:
                    matrix.postRotate(90);
                    break;
                case 3:
                    matrix.postRotate(180);
                    break;
                case 8:
                    matrix.postRotate(270);
                    break;
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        scaledPhoto = Bitmap.createBitmap(photo, 0, 0, photo.getWidth(), photo.getHeight(), matrix, true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        scaledPhoto.compress(Bitmap.CompressFormat.JPEG, result.quality, bytes);

        final boolean forceLocal = requestCode == REQUEST_LAUNCH_IMAGE_CAPTURE;
        final File resized = createNewFile(context, options, !forceLocal);

        if (resized == null)
        {
            if (photo != null)
            {
                photo.recycle();
                photo = null;
            }
            if (scaledPhoto != null)
            {
                scaledPhoto.recycle();
                scaledPhoto = null;
            }
            return imageConfig;
        }

        result = result.withResizedFile(resized);

        try (FileOutputStream fos = new FileOutputStream(result.resized))
        {
            bytes.writeTo(fos);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        if (photo != null)
        {
            photo.recycle();
            photo = null;
        }
        if (scaledPhoto != null)
        {
            scaledPhoto.recycle();
            scaledPhoto = null;
        }
        return result;
    }

    public static void removeUselessFiles(final int requestCode,
                                          @NonNull final ImageConfig imageConfig)
    {
        if (requestCode != ImagePickerModule.REQUEST_LAUNCH_IMAGE_CAPTURE)
        {
            return;
        }

        if (imageConfig.original != null && imageConfig.original.exists())
        {
            imageConfig.original.delete();
        }

        if (imageConfig.resized != null && imageConfig.resized.exists())
        {
            imageConfig.resized.delete();
        }
    }

    public static void fileScan(@Nullable final Context reactContext,
                                @NonNull final String path)
    {
        if (reactContext == null)
        {
            return;
        }
        MediaScannerConnection.scanFile(reactContext,
                new String[] { path }, null,
                new MediaScannerConnection.OnScanCompletedListener()
                {
                    public void onScanCompleted(String path, Uri uri)
                    {
                        Log.i("TAG", new StringBuilder("Finished scanning ").append(path).toString());
                    }
                });
    }

    //如果exif中获取不到，从Provider中读取
    private static long read_DATE_ADDED_From_uri(Uri uri, Activity activity){
        Cursor c = activity.getContentResolver().query(uri, null, null, null, null, null);
        if (c ==null || c.getCount()<=0){
            return -1;
        }
        c.moveToFirst();
        long date_added = c.getLong(c.getColumnIndex(MediaStore.Images.ImageColumns.DATE_ADDED));
        return date_added;
    }

    //这个ts单位可能是秒（三星、android文档），可能是毫秒(小米上)。先尝试秒，再尝试毫秒
    private static Date dateFromTS(long ts){
        Calendar cal = Calendar.getInstance();

        Date date = new Date(ts*1000);//先假设ts单位是秒
        cal.setTime(date);
        int year = cal.get(Calendar.YEAR);
        android.util.Log.d(TAG, "dateFromTS: year0="+year);
        if (year >= 1971 && year<=2100){
            return date;
        }

        date = new Date(ts);//再假设ts单位是ms
        cal.setTime(date);
        year = cal.get(Calendar.YEAR);
        android.util.Log.d(TAG, "dateFromTS: year1="+year);
        if (year >= 1971 && year<=2100){
            return date;
        }

        return null;
    }

    public static ReadExifResult readExifInterface(@NonNull ResponseHelper responseHelper,
                                                   @NonNull final ImageConfig imageConfig,
                                                   @NonNull final Uri uri,
                                                   @NonNull Activity activity)
    {
        ReadExifResult result;
        int currentRotation = 0;

        try
        {
            ExifInterface exif = new ExifInterface(imageConfig.original.getAbsolutePath());

            // extract lat, long, and timestamp and add to the response
            float[] latlng = new float[2];
            exif.getLatLong(latlng);
            float latitude = latlng[0];
            float longitude = latlng[1];
            if(latitude != 0f || longitude != 0f)
            {
                responseHelper.putDouble("latitude", latitude);
                responseHelper.putDouble("longitude", longitude);
            }

            final String timestamp = exif.getAttribute(ExifInterface.TAG_DATETIME);
            final SimpleDateFormat exifDatetimeFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");

            final DateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

            try
            {
                final String isoFormatString = new StringBuilder(isoFormat.format(exifDatetimeFormat.parse(timestamp)))
                        .append("Z").toString();
                responseHelper.putString("timestamp", isoFormatString);
                android.util.Log.d(TAG, "readExifInterface exif中的timestamp: "+isoFormatString);

            }
            catch (Exception e) {
                android.util.Log.d(TAG, "readExifInterface 读取exif中的timestamp异常 ",e);
            }

            //如果没有timestamp，从Provider中取
            try{
                if (!responseHelper.getResponse().hasKey("timestamp")){
                    long date_added = read_DATE_ADDED_From_uri(uri,activity); //单位是ms
                    android.util.Log.d(TAG, "readExifInterface: date_added"+date_added);
                    Date date = MediaUtils.dateFromTS(date_added);
                    android.util.Log.d(TAG, "readExifInterface: date:"+date);
                    final String isoFormatString = new StringBuilder(isoFormat.format(date))
                            .append("Z").toString();
                    responseHelper.putString("timestamp", isoFormatString);
                    android.util.Log.d(TAG, "readExifInterface date_added isoFormatString: "+isoFormatString);
                }
            } catch (Exception e) {
                android.util.Log.d(TAG, "readExifInterface date_added异常: ",e);
            }


            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            boolean isVertical = true;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_270:
                    isVertical = false;
                    currentRotation = 270;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_90:
                    isVertical = false;
                    currentRotation = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    currentRotation = 180;
                    break;
            }
            responseHelper.putInt("originalRotation", currentRotation);
            responseHelper.putBoolean("isVertical", isVertical);
            result = new ReadExifResult(currentRotation, null);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            result = new ReadExifResult(currentRotation, e);
        }

        return result;
    }

    public static @Nullable RolloutPhotoResult rolloutPhotoFromCamera(@NonNull final ImageConfig imageConfig)
    {
        RolloutPhotoResult result = null;
        final File oldFile = imageConfig.resized == null ? imageConfig.original: imageConfig.resized;
        final File newDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        final File newFile = new File(newDir.getPath(), oldFile.getName());

        try
        {
            moveFile(oldFile, newFile);
            ImageConfig newImageConfig;
            if (imageConfig.resized != null)
            {
                newImageConfig = imageConfig.withResizedFile(newFile);
            }
            else
            {
                newImageConfig = imageConfig.withOriginalFile(newFile);
            }
            result = new RolloutPhotoResult(newImageConfig, null);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            result = new RolloutPhotoResult(imageConfig, e);
        }
        return result;
    }

    /**
     * Move a file from one location to another.
     *
     * This is done via copy + deletion, because Android will throw an error
     * if you try to move a file across mount points, e.g. to the SD card.
     */
    private static void moveFile(@NonNull final File oldFile,
                                 @NonNull final File newFile) throws IOException
    {
        FileChannel oldChannel = null;
        FileChannel newChannel = null;

        try
        {
            oldChannel = new FileInputStream(oldFile).getChannel();
            newChannel = new FileOutputStream(newFile).getChannel();
            oldChannel.transferTo(0, oldChannel.size(), newChannel);

            oldFile.delete();
        }
        finally
        {
            try
            {
                if (oldChannel != null) oldChannel.close();
                if (newChannel != null) newChannel.close();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }


    public static class RolloutPhotoResult
    {
        public final ImageConfig imageConfig;
        public final Throwable error;

        public RolloutPhotoResult(@NonNull final ImageConfig imageConfig,
                                  @Nullable final Throwable error)
        {
            this.imageConfig = imageConfig;
            this.error = error;
        }
    }


    public static class ReadExifResult
    {
        public final int currentRotation;
        public final Throwable error;

        public ReadExifResult(int currentRotation,
                              @Nullable final Throwable error)
        {
            this.currentRotation = currentRotation;
            this.error = error;
        }
    }
}
