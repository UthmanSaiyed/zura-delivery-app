package com.example.zura

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.*
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
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
import kotlin.math.*

/**
 * This class displays available delivery orders for drivers to accept and manage.
 *
 * This activity gives the code for:
 * - A map view showing available orders and navigation routes
 * - A list of available orders with details (store, customer, distance, price)
 * - Functionality to accept/decline orders
 * - Order status tracking (to store, to customer, delivery verification)
 * - Route navigation with animated motorbike marker
 * - Delivery PIN verification system
 *
 * The activity implements OnMapReadyCallback for Google Maps integration.
 */
class AvailableOrdersActivity : AppCompatActivity(), OnMapReadyCallback {

    /** Google Map instance for displaying locations and routes */
    private lateinit var map: GoogleMap

    /** Firestore database instance */
    private val firestore = FirebaseFirestore.getInstance()

    /** Current driver's user ID */
    private val driverId = FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"

    /** Set of order IDs that have been declined by the driver */
    private val declinedOrderIds = mutableSetOf<String>()

    /** Layout container for displaying order cards */
    private lateinit var ordersLayout: LinearLayout

    /** Default driver location (Gateway House coordinates) */
    private val driverLatLng = LatLng(52.62952818500918, -1.1380281441788296)

    /** ID of the currently active order */
    private var currentOrderId: String? = null

    /** Store location for the current order */
    private var storeLatLng: LatLng? = null

    /** Customer location for the current order */
    private var customerLatLng: LatLng? = null

    /** Current delivery stage ("none", "to_store", "to_customer") */
    private var currentStage = "none"

    /** API key for Google Directions API */
    private val directionsApiKey = "AIzaSyAFiDWBKoumVwYdF4mH6S7bCrkjJaAdfmI"

    /** Marker representing the driver's motorbike on the map */
    private var motorbikeMarker: Marker? = null

    /** Handler for managing UI thread operations */
    private val handler = Handler(Looper.getMainLooper())

    /** List of points that make up the current navigation route */
    private var currentRoutePoints: List<LatLng> = emptyList()

    /** Current index for route animation */
    private var animationIndex = 0

    /**
     * Called when the activity is first created.
     * Sets up the UI and initializes the map fragment.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_available_orders)
        ordersLayout = findViewById(R.id.ordersContainer)
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    /**
     * Callback for when the Google Map is ready to be used.
     * Sets up initial map configuration and loads available orders.
     *
     * @param googleMap The GoogleMap instance that is ready
     */
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled = true
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(driverLatLng, 13f))

        // Add marker for driver's current location
        map.addMarker(
            MarkerOptions()
                .position(driverLatLng)
                .title("You (Gateway House)")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
        )

        loadOrders()
    }

    /**
     * Loads available orders from Firestore and displays them in the UI.
     * Filters out orders that have been declined by the driver.
     */
    private fun loadOrders() {
        firestore.collection("orders")
            .whereEqualTo("status", "pending")
            .get()
            .addOnSuccessListener { snapshot ->
                ordersLayout.removeAllViews()
                map.clear()
                // Re-add driver marker
                map.addMarker(MarkerOptions().position(driverLatLng).title("You (Gateway House)").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)))

                if (snapshot.isEmpty) {
                    val emptyMsg = TextView(this).apply {
                        text = "No orders available at the moment."
                        textSize = 18f
                        setTextColor(Color.DKGRAY)
                        gravity = Gravity.CENTER
                    }
                    ordersLayout.addView(emptyMsg)
                    return@addOnSuccessListener
                }

                // Process each available order
                for (doc in snapshot) {
                    val orderId = doc.id
                    if (declinedOrderIds.contains(orderId)) continue

                    val data = doc.data
                    val customerGeo = data["location"] as? com.google.firebase.firestore.GeoPoint ?: continue
                    val storeName = data["store"] as? String ?: continue
                    val customerName = data["userName"] as? String ?: "Customer"
                    val address = data["address"] as? String ?: "Unknown"
                    val customerPos = LatLng(customerGeo.latitude, customerGeo.longitude)

                    // Get store location details
                    firestore.collection("stores").document(storeName).get().addOnSuccessListener { storeDoc ->
                        val storeLat = storeDoc.getDouble("latitude") ?: return@addOnSuccessListener
                        val storeLng = storeDoc.getDouble("longitude") ?: return@addOnSuccessListener
                        val storePos = LatLng(storeLat, storeLng)

                        // Calculate distance and price
                        val distanceToStore = FloatArray(1)
                        android.location.Location.distanceBetween(
                            driverLatLng.latitude, driverLatLng.longitude,
                            storeLat, storeLng,
                            distanceToStore
                        )
                        val miles = distanceToStore[0] * 0.000621371
                        val price = (miles * 3).roundToInt()

                        // Create order card UI
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(24, 24, 24, 24)
                            setBackgroundColor(Color.parseColor("#F5F5F5"))
                            val params = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            params.setMargins(0, 0, 0, 24)
                            layoutParams = params
                        }

                        // Helper function to add text lines to the card
                        fun addLine(label: String, value: String) = TextView(this).apply {
                            text = "$label $value"
                            textSize = 16f
                            setTextColor(Color.DKGRAY)
                        }

                        // Add order details to card
                        card.addView(addLine("Store:", storeName))
                        card.addView(addLine("Customer:", customerName))
                        card.addView(addLine("Address:", address))
                        card.addView(addLine("Distance:", String.format("%.2f mi", miles)))
                        card.addView(addLine("Delivery Price:", "£$price"))

                        // Create action buttons
                        val buttonRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.END
                            setPadding(0, 16, 0, 0)
                        }

                        val declineBtn = Button(this).apply {
                            text = "Decline"
                            setBackgroundColor(Color.RED)
                            setTextColor(Color.WHITE)
                            setOnClickListener {
                                declinedOrderIds.add(orderId)
                                loadOrders()
                            }
                        }

                        val acceptBtn = Button(this).apply {
                            text = "Accept"
                            setBackgroundColor(Color.parseColor("#388E3C"))
                            setTextColor(Color.WHITE)
                            setOnClickListener {
                                currentOrderId = orderId
                                storeLatLng = storePos
                                customerLatLng = customerPos
                                currentStage = "to_store"
                                // Update order status in Firestore
                                firestore.collection("orders").document(orderId)
                                    .update("status", "driver collecting goods", "driverId", driverId)
                                    .addOnSuccessListener {
                                        Toast.makeText(this@AvailableOrdersActivity, "Order accepted", Toast.LENGTH_SHORT).show()
                                        drawRouteWithDirections(driverLatLng, storePos)
                                        showStageButtons()
                                    }
                            }
                        }

                        buttonRow.addView(declineBtn)
                        buttonRow.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(24, 1) })
                        buttonRow.addView(acceptBtn)
                        card.addView(buttonRow)
                        ordersLayout.addView(card)
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load orders", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Shows the UI for the "to store" stage after accepting an order.
     * Provides a button to mark the order as collected.
     */
    private fun showStageButtons() {
        ordersLayout.removeAllViews()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.WHITE)
        }

        val collectedBtn = Button(this).apply {
            text = "Order Collected"
            setBackgroundColor(Color.BLUE)
            setTextColor(Color.WHITE)
            setOnClickListener {
                currentStage = "to_customer"
                firestore.collection("orders").document(currentOrderId!!)
                    .update("status", "driver has collected goods and is on his way")
                    .addOnSuccessListener {
                        Toast.makeText(this@AvailableOrdersActivity, "Heading to customer", Toast.LENGTH_SHORT).show()
                        drawRouteWithDirections(storeLatLng!!, customerLatLng!!)
                        showDeliveryButton()
                    }
            }
        }

        layout.addView(collectedBtn)
        ordersLayout.addView(layout)
    }

    /**
     * Shows the UI for the "to customer" stage after collecting the order.
     * Provides a button to mark the order as delivered and verify the delivery PIN.
     */
    private fun showDeliveryButton() {
        ordersLayout.removeAllViews()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.WHITE)
        }

        val deliveredBtn = Button(this).apply {
            text = "Order Delivered"
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            setOnClickListener {
                val input = EditText(this@AvailableOrdersActivity).apply {
                    hint = "Enter 4-digit delivery PIN"
                    filters = arrayOf(InputFilter.LengthFilter(4)) // Limit to 4 digits
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD // Show numeric keypad, mask input
                    gravity = Gravity.CENTER
                }

                val dialog = AlertDialog.Builder(this@AvailableOrdersActivity)
                    .setTitle("Delivery Verification")
                    .setMessage("Please enter the customer's delivery PIN.")
                    .setView(input)
                    .setPositiveButton("Verify") { _, _ ->
                        val enteredPin = input.text.toString().trim()
                        if (enteredPin.length == 4) {
                            firestore.collection("orders").document(currentOrderId!!).get()
                                .addOnSuccessListener { doc ->
                                    val correctPin = doc.getString("deliveryPin") ?: ""
                                    if (enteredPin == correctPin) {
                                        Toast.makeText(this@AvailableOrdersActivity, "Verifying PIN...", Toast.LENGTH_SHORT).show()
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            firestore.collection("orders").document(currentOrderId!!)
                                                .update("status", "order completed")
                                                .addOnSuccessListener {
                                                    Toast.makeText(this@AvailableOrdersActivity, "Delivery completed!", Toast.LENGTH_SHORT).show()
                                                    currentOrderId = null
                                                    currentStage = "none"
                                                    stopMotorbikeAnimation()
                                                    loadOrders()
                                                }
                                        }, 2000)
                                    } else {
                                        Toast.makeText(this@AvailableOrdersActivity, "Incorrect PIN. Try again.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                        } else {
                            Toast.makeText(this@AvailableOrdersActivity, "Please enter a valid 4-digit PIN", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .create()

                dialog.show()
            }
        }

        layout.addView(deliveredBtn)
        ordersLayout.addView(layout)
    }

    /**
     * Draws a route between two points using Google Directions API.
     *
     * @param start The starting LatLng point
     * @param end The destination LatLng point
     */
    private fun drawRouteWithDirections(start: LatLng, end: LatLng) {
        map.clear()
        val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${start.latitude},${start.longitude}" +
                "&destination=${end.latitude},${end.longitude}&key=$directionsApiKey"

        val queue = Volley.newRequestQueue(this)
        val request = StringRequest(Request.Method.GET, url,
            { response ->
                val json = JSONObject(response)
                val routes = json.getJSONArray("routes")
                if (routes.length() == 0) {
                    Toast.makeText(this, "No route found", Toast.LENGTH_SHORT).show()
                    return@StringRequest
                }

                // Parse route steps
                val steps = routes.getJSONObject(0).getJSONArray("legs")
                    .getJSONObject(0).getJSONArray("steps")

                val points = mutableListOf<LatLng>()
                for (i in 0 until steps.length()) {
                    val polyline = steps.getJSONObject(i).getJSONObject("polyline").getString("points")
                    points.addAll(decodePolyline(polyline))
                }

                // Draw the route on the map
                val polylineOptions = PolylineOptions()
                    .addAll(points)
                    .width(12f)
                    .color(Color.MAGENTA)
                map.addPolyline(polylineOptions)

                // Add markers for start and end points
                map.addMarker(MarkerOptions().position(start).title("Start").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)))
                map.addMarker(MarkerOptions().position(end).title("Destination").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))

                // Zoom to show the entire route
                val bounds = LatLngBounds.Builder().include(start).include(end).build()
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))

                currentRoutePoints = points
                animateMotorbike(points)
            },
            {
                Toast.makeText(this, "Route load failed", Toast.LENGTH_SHORT).show()
            })

        queue.add(request)
    }

    /**
     * Animates a motorbike marker along the given route points.
     *
     * @param route List of LatLng points representing the route
     */
    private fun animateMotorbike(route: List<LatLng>) {
        stopMotorbikeAnimation()
        if (route.isEmpty()) return

        val motorbikeIcon = bitmapDescriptorFromVector(R.drawable.baseline_directions_bike_24)
        motorbikeMarker = map.addMarker(MarkerOptions().position(route[0]).icon(motorbikeIcon).anchor(0.5f, 0.5f).flat(true))
        animationIndex = 0

        val delayPerStep: Long = 1000L

        handler.post(object : Runnable {
            override fun run() {
                if (animationIndex >= route.size) return
                val nextPoint = route[animationIndex]
                motorbikeMarker?.position = nextPoint
                animationIndex++
                handler.postDelayed(this, delayPerStep)
            }
        })
    }

    /**
     * Stops the motorbike animation and removes the marker from the map.
     */
    private fun stopMotorbikeAnimation() {
        handler.removeCallbacksAndMessages(null)
        motorbikeMarker?.remove()
        motorbikeMarker = null
        animationIndex = 0
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
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dLat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
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
        val wrappedDrawable = DrawableCompat.wrap(vectorDrawable)
        val bitmap = Bitmap.createBitmap(
            wrappedDrawable.intrinsicWidth,
            wrappedDrawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        wrappedDrawable.setBounds(0, 0, canvas.width, canvas.height)
        wrappedDrawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}