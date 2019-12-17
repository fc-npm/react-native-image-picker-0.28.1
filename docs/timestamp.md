# timestamp说明
# android
+ 在安卓中会先从exif中读取，如果exif中没有找到，再使用DATE_ADDED（文件创建时间）
+ 代码见 android/src/main/java/com/imagepicker/utils/MediaUtils.java

# ios
+ 不使用exif，总是使用文件创建时间。
+ 代码为
```
PHAsset *capturedAsset = [PHAsset fetchAssetsWithALAssetURLs:@[assetURL] options:nil].lastObject;
self.response[@"timestamp"] = [[ImagePickerManager ISO8601DateFormatter] stringFromDate:capturedAsset.creationDate];
```
