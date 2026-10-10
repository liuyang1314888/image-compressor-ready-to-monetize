package com.imagecompressor.app

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.AdRequest
import com.android.billingclient.api.*
import java.io.ByteArrayOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity(), PurchasesUpdatedListener {

    private lateinit var adView: AdView
    private var interstitialAd: InterstitialAd? = null

    // Billing
    private lateinit var billingClient: BillingClient
    private var removeAdsSku: SkuDetails? = null

    private val pickImages = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris == null || uris.isEmpty()) return@registerForActivityResult
        // Start compression on background thread
        Thread {
            runOnUiThread { Toast.makeText(this, "开始压缩 ${uris.size} 张图片", Toast.LENGTH_SHORT).show() }
            var count = 0
            for (uri in uris) {
                compressAndSave(uri)
                count++
            }
            runOnUiThread {
                Toast.makeText(this, "已压缩 $count 张图片", Toast.LENGTH_LONG).show()
                interstitialAd?.show(this)
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        MobileAds.initialize(this) {}

        adView = findViewById(R.id.adView)
        adView.adSize = AdSize.BANNER
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)

        // Load Interstitial (test ad unit)
        InterstitialAd.load(this, "ca-app-pub-3940256099942544/1033173712", adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitialAd = ad
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                interstitialAd = null
                Log.w("AdMob", "Interstitial failed: ${error.message}")
            }
        })

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            // Open picker for images
            pickImages.launch(arrayOf("image/*"))
        }

        findViewById<Button>(R.id.btnCompressSample).setOnClickListener {
            // For demo: pick one sample image from resources and compress (not implemented)
            Toast.makeText(this, "示例压缩（请从相册选择图片）", Toast.LENGTH_SHORT).show()
        }

        // Billing setup
        billingClient = BillingClient.newBuilder(this).enablePendingPurchases().setListener(this).build()
        billingClient.startConnection(object: BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val params = SkuDetailsParams.newBuilder()
                        .setSkusList(listOf("remove_ads"))
                        .setType(BillingClient.SkuType.INAPP)
                        .build()
                    billingClient.querySkuDetailsAsync(params) { result, skuDetailsList ->
                        if (result.responseCode == BillingClient.BillingResponseCode.OK && skuDetailsList != null) {
                            if (skuDetailsList.isNotEmpty()) removeAdsSku = skuDetailsList[0]
                        }
                    }
                }
            }
            override fun onBillingServiceDisconnected() {
                // retry logic
            }
        })

        findViewById<Button>(R.id.btnBuy).setOnClickListener {
            removeAdsSku?.let { sku ->
                val flowParams = BillingFlowParams.newBuilder().setSkuDetails(sku).build()
                billingClient.launchBillingFlow(this, flowParams)
            } ?: Toast.makeText(this, "购买未准备好（在 Play Console 创建商品并用内部测试渠道安装应用以测试）", Toast.LENGTH_LONG).show()
        }
    }

    private fun compressAndSave(uri: Uri) {
        try {
            val resolver: ContentResolver = contentResolver
            val input: InputStream? = resolver.openInputStream(uri)
            val original = BitmapFactory.decodeStream(input)
            input?.close()
            // compress to JPEG quality 75
            val baos = ByteArrayOutputStream()
            original.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            val bytes = baos.toByteArray()

            // save to MediaStore (scoped storage friendly)
            val filename = "img_compressed_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ImageCompressor")
                }
            }
            val uriOut: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uriOut?.let { outUri ->
                resolver.openOutputStream(outUri)?.use { os ->
                    os.write(bytes)
                    os.flush()
                }
            }
        } catch (e: Exception) {
            Log.e("Compress", "error: ${e.message}")
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                // Acknowledge if required
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    if (!purchase.isAcknowledged) {
                        val ack = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                        billingClient.acknowledgePurchase(ack) { ackResult ->
                            // unlock remove-ads in local prefs
                            // TODO: persist purchase state
                        }
                    }
                }
            }
        } else {
            Log.w("Billing", "purchase failed: ${billingResult.debugMessage}")
        }
    }
}
