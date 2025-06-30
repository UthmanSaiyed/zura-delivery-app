package com.example.zura

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.android.volley.Request
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * This class displays tracking the status and location of an active order.
 *
 * This activity provides:
 * - Real-time visualisation of the delivery route on a map
 * - Animated motorbike marker showing driver's progress
 * - Detailed order information display
 * - Automatic updates when order status changes
 *
 * The activity implements OnMapReadyCallback for Google Maps integration.
 */
class TrackOrderActivity : AppCompatActivity(), OnMapReadyCallback {

    /** Google Map instance for displaying route and location */
    private lateinit var map: GoogleMap

    /** Firestore database instance */
    private val firestore = FirebaseFirestore.getInstance()

    /** Current customer's user ID */
    private val customerId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    /** API key for Google Directions API */
    private val directionsApiKey = "AIzaSyAFiDWBKoumVwYdF4mH6S7bCrkjJaAdfmI"

    /** Marker representing the delivery driver's motorbike */
    private var motorbikeMarker: Marker? = null

    /** Layout container for order details */
    private lateinit var orderDetailsLayout: LinearLayout

    /** List of points that make up the current route */
    private var currentRoute: List<LatLng> = emptyList()

    /** Handler for managing UI thread operations */
    private val handler = Handler(Looper.getMainLooper())

    /** Current index for route animation */
    private var animationIndex = 0

    /**
     * Called when the activity is first created.
     * Sets up the UI and initializes the map fragment.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_track_order)

        orderDetailsLayout = findViewById(R.id.orderDetailsLayout)
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    /**
     * Callback for when the Google Map is ready to be used.
     * Sets up initial map configuration and loads active order data.
     *
     * @param googleMap The GoogleMap instance that is ready
     */
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled = true
        loadActiveOrder()
    }

    /**
     * Loads the customer's active order from Firestore.
     * Only shows orders that are in progress (not completed or cancelled).
     */
    private fun loadActiveOrder() {
        firestore.collection("orders")
            .whereEqualTo("userId", customerId)
            .whereIn("status", listOf(
                "driver collecting goods",
                "driver has collected goods and is on his way"
            ))
            .limit(1)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) {
                    showNoOrderMessage()
                    return@addOnSuccessListener
                }

                val orderDoc = snapshot.documents[0]
                val data = orderDoc.data ?: return@addOnSuccessListener
                displayOrderData(data, orderDoc.id)
            }
            .addOnFailureListener {
                showNoOrderMessage()
            }
    }

    /**
     * Displays a message when no active order is found.
     */
    private fun showNoOrderMessage() {
        orderDetailsLayout.removeAllViews()
        orderDetailsLayout.addView(TextView(this).apply {
            text = "No active order to track"
            textSize = 18f
            setPadding(16, 16, 16, 16)
        })
    }

    /**
     * Processes and displays order data including route visualization.
     *
     * @param data Map containing the order data
     * @param orderId The ID of the order being tracked
     */
    private fun displayOrderData(data: Map<String, Any>, orderId: String) {
        val storeName = data["store"] as? String ?: return
        val note = data["note"] as? String ?: ""
        val status = data["status"] as? String ?: ""
        val timestamp = (data["timestamp"] as? com.google.firebase.Timestamp)?.toDate()
        val formattedTime = timestamp?.let {
            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(it)
        } ?: "Unknown"

        val customerGeo = data["location"] as? com.google.firebase.firestore.GeoPoint ?: return
        val customerLatLng = LatLng(customerGeo.latitude, customerGeo.longitude)

        // Get store location from Firestore
        firestore.collection("stores").document(storeName).get()
            .addOnSuccessListener { storeDoc ->
                val storeLat = storeDoc.getDouble("latitude") ?: return@addOnSuccessListener
                val storeLng = storeDoc.getDouble("longitude") ?: return@addOnSuccessListener
                val storeLatLng = LatLng(storeLat, storeLng)

                drawRoute(storeLatLng, customerLatLng)
                displayOrderDetails(storeName, note, orderId, formattedTime, status)
            }
            .addOnFailureListener {
                showNoOrderMessage()
            }
    }

    /**
     * Draws the delivery route between two points using Google Directions API.
     *
     * @param start The starting LatLng (store location)
     * @param end The destination LatLng (customer location)
     */
    private fun drawRoute(start: LatLng, end: LatLng) {
        val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${start.latitude},${start.longitude}" +
                "&destination=${end.latitude},${end.longitude}&key=$directionsApiKey"

        val queue = Volley.newRequestQueue(this)
        val request = StringRequest(Request.Method.GET, url, { response ->
            try {
                val json = JSONObject(response)
                val routes = json.getJSONArray("routes")
                if (routes.length() == 0) {
                    Toast.makeText(this, "No route found", Toast.LENGTH_SHORT).show()
                    return@StringRequest
                }

                // Parse route steps to get polyline points
                val steps = routes.getJSONObject(0).getJSONArray("legs")
                    .getJSONObject(0).getJSONArray("steps")

                val points = mutableListOf<LatLng>()
                for (i in 0 until steps.length()) {
                    val polyline = steps.getJSONObject(i).getJSONObject("polyline").getString("points")
                    points.addAll(decodePolyline(polyline))
                }

                // Draw the route on map
                map.addPolyline(
                    PolylineOptions()
                        .addAll(points)
                        .color(android.graphics.Color.BLUE)
                        .width(10f)
                )
                map.addMarker(MarkerOptions().position(end).title("Delivery Address"))
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 13f))

                currentRoute = points
                animateMotorbike()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to parse route", Toast.LENGTH_SHORT).show()
            }
        }, {
            Toast.makeText(this, "Failed to load route", Toast.LENGTH_SHORT).show()
        })

        queue.add(request)
    }

    /**
     * Animates the motorbike marker along the calculated route.
     * The animation runs at approximately 20mph (700ms delay between points).
     */
    private fun animateMotorbike() {
        handler.removeCallbacksAndMessages(null)
        if (currentRoute.isEmpty()) return

        val icon = bitmapDescriptorFromVector(R.drawable.baseline_directions_bike_24)
        motorbikeMarker = map.addMarker(
            MarkerOptions()
                .position(currentRoute[0])
                .icon(icon)
                .anchor(0.5f, 0.5f)
                .flat(true)
        )

        animationIndex = 0
        handler.post(object : Runnable {
            override fun run() {
                if (animationIndex >= currentRoute.size) return
                motorbikeMarker?.position = currentRoute[animationIndex++]
                handler.postDelayed(this, 700L) // ~20mph
            }
        })
    }

    /**
     * Displays order details in a card view.
     *
     * @param store The store name
     * @param note Delivery notes
     * @param orderId The order ID
     * @param time Formatted order time
     * @param status Current order status
     */
    private fun displayOrderDetails(store: String, note: String, orderId: String, time: String, status: String) {
        orderDetailsLayout.removeAllViews()

        val cardView = CardView(this).apply {
            radius = 10f
            setContentPadding(20, 20, 20, 20)
            cardElevation = 8f
        }

        val linearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Create fields to display
        val fields = listOf(
            "Store: $store",
            "Order ID: $orderId",
            "Note: $note",
            "Time Ordered: $time",
            "Status: $status"
        )

        // Add each field as a TextView
        for (line in fields) {
            val textView = TextView(this).apply {
                text = line
                textSize = 16f
                setPadding(0, 8, 0, 8)
            }
            linearLayout.addView(textView)
        }

        cardView.addView(linearLayout)
        orderDetailsLayout.addView(cardView)
    }

    /**
     * Decodes a polyline string into a list of LatLng points.
     *
     * @param encoded The encoded polyline string
     * @return List of decoded LatLng points
     */
    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dLat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dLng

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }

        return poly
    }

    /**
     * Creates a BitmapDescriptor from a vector drawable resource.
     *
     * @param vectorResId The resource ID of the vector drawable
     * @return BitmapDescriptor created from the vector
     */
    private fun bitmapDescriptorFromVector(vectorResId: Int): BitmapDescriptor {
        val vectorDrawable = ContextCompat.getDrawable(this, vectorResId)!!
        val wrapped = DrawableCompat.wrap(vectorDrawable)
        val bitmap = Bitmap.createBitmap(
            wrapped.intrinsicWidth,
            wrapped.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        wrapped.setBounds(0, 0, canvas.width, canvas.height)
        wrapped.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}