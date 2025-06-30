package com.example.zura

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.os.*
import android.text.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import java.util.*
import kotlin.math.*

/**
 * This class displays confirming and placing delivery orders.
 *
 * This activity handles:
 * - Address selection via Places API autocomplete
 * - Payment information collection and validation
 * - Distance calculation and pricing
 * - Order confirmation and submission to Firestore
 * - Success feedback and navigation
 *
 * The activity includes credit card input masking and validation,
 * as well as delivery location selection and price calculation.
 */
class ConfirmOrderActivity : AppCompatActivity() {

    // UI Components
    private lateinit var confirmButton: Button
    private lateinit var orderDetailsText: TextView
    private lateinit var priceText: TextView
    private lateinit var paymentText: TextView
    private lateinit var cardInput: EditText
    private lateinit var expiryInput: EditText
    private lateinit var cvvInput: EditText

    // Firebase instances
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Location and order data
    private var userLat: Double? = null
    private var userLon: Double? = null
    private var selectedAddress: String? = null
    private var calculatedPrice = 0.0
    private var allInputsValid = false
    private var lastOrderNumber = ""

    /**
     * Called when the activity is first created.
     * Sets up the UI components and initializes the address autocomplete.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirm_order)

        // Initialize UI components
        confirmButton = findViewById(R.id.confirmBtn)
        orderDetailsText = findViewById(R.id.orderDetailsText)
        priceText = findViewById(R.id.priceText)
        paymentText = findViewById(R.id.paymentText)
        cardInput = findViewById(R.id.card_number_input)
        expiryInput = findViewById(R.id.expiry_date_input)
        cvvInput = findViewById(R.id.cvv_input)

        confirmButton.isEnabled = false

        // Set up input fields with validation and masking
        setupCardInput()
        setupExpiryInput()
        setupCVVInput()

        // Get order details from intent
        val store = intent.getStringExtra("store")
        val receiptNumber = intent.getStringExtra("receiptNumber")
        val note = intent.getStringExtra("note")

        // Validate required order details
        if (store == null || receiptNumber == null || note == null) {
            toast("Missing order details")
            finish()
            return
        }

        // Display order details
        orderDetailsText.text = "Store: $store\nReceipt #: $receiptNumber\nNote: $note"

        // Initialize Places API if not already initialized
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, getString(R.string.google_maps_key))
        }

        // Set up address autocomplete fragment
        val autocompleteFragment = AutocompleteSupportFragment.newInstance().apply {
            setPlaceFields(listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS))
            setOnPlaceSelectedListener(object : PlaceSelectionListener {
                /**
                 * Called when a place is selected from the autocomplete results.
                 * Calculates delivery distance and price based on store location.
                 */
                override fun onPlaceSelected(place: Place) {
                    place.latLng?.let {
                        selectedAddress = place.address ?: place.name ?: "Unknown"
                        userLat = it.latitude
                        userLon = it.longitude

                        // Get store location and calculate delivery distance/price
                        db.collection("stores").whereEqualTo("name", store).get()
                            .addOnSuccessListener { docs ->
                                docs.firstOrNull()?.let { doc ->
                                    val storeLat = doc.getDouble("latitude") ?: return@let
                                    val storeLon = doc.getDouble("longitude") ?: return@let
                                    val dist = calculateDistanceMiles(storeLat, storeLon, userLat!!, userLon!!)
                                    calculatedPrice = dist * 3
                                    priceText.text = "Distance: %.2f mi\nDelivery Price: £%.2f".format(dist, calculatedPrice)
                                } ?: toast("Store not found")
                            }.addOnFailureListener { toast("Failed to get store data") }
                    }
                }

                /**
                 * Called when there's an error with the Places API.
                 */
                override fun onError(status: com.google.android.gms.common.api.Status) {
                    toast("Error: ${status.statusMessage}")
                }
            })
        }

        // Add the autocomplete fragment to the UI
        supportFragmentManager.beginTransaction()
            .replace(R.id.autocomplete_fragment_container, autocompleteFragment)
            .commitNow()

        // Set up order confirmation button
        confirmButton.setOnClickListener {
            val user = auth.currentUser
            if (user == null || !allInputsValid || userLat == null || userLon == null || selectedAddress == null) {
                toast("Please complete all fields correctly")
                return@setOnClickListener
            }

            // Show processing dialog
            val progressDialog = ProgressDialog(this).apply {
                setMessage("Processing Payment...")
                setCancelable(false)
                show()
            }

            // Simulate payment processing delay
            Handler(Looper.getMainLooper()).postDelayed({
                // Get user details from Firestore
                db.collection("users").document(user.uid).get().addOnSuccessListener { userDoc ->
                    val fullName = userDoc.getString("fullName") ?: "Unknown"
                    val email = user.email ?: "Unknown"
                    val deliveryPin = userDoc.getString("deliveryPin") ?: "0000"
                    lastOrderNumber = UUID.randomUUID().toString().take(8).uppercase()

                    // Create order data
                    val data = hashMapOf(
                        "userId" to user.uid,
                        "userName" to fullName,
                        "email" to email,
                        "store" to store,
                        "receiptNumber" to receiptNumber,
                        "note" to note,
                        "location" to GeoPoint(userLat!!, userLon!!),
                        "address" to selectedAddress,
                        "timestamp" to Timestamp.now(),
                        "orderNumber" to lastOrderNumber,
                        "status" to "pending",
                        "price" to calculatedPrice,
                        "deliveryDriverId" to null,
                        "deliveryPin" to deliveryPin
                    )

                    // Submit order to Firestore
                    db.collection("orders").add(data)
                        .addOnSuccessListener {
                            progressDialog.dismiss()
                            showSuccessDialog()
                        }.addOnFailureListener {
                            progressDialog.dismiss()
                            toast("Order failed: ${it.message}")
                        }
                }
            }, 3000)
        }
    }

    /**
     * Sets up the credit card input field with formatting and masking.
     */
    private fun setupCardInput() {
        cardInput.inputType = InputType.TYPE_CLASS_NUMBER
        cardInput.filters = arrayOf(InputFilter.LengthFilter(19))
        var maskHandler: Handler? = null
        var lastFormatted = ""
        cardInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                cardInput.removeTextChangedListener(this)
                val digits = s.toString().replace("-", "").take(16)
                val formatted = digits.chunked(4).joinToString("-")
                if (formatted != lastFormatted) {
                    cardInput.setText(formatted)
                    cardInput.setSelection(formatted.length)
                    lastFormatted = formatted
                }
                maskHandler?.removeCallbacksAndMessages(null)
                maskHandler = Handler(Looper.getMainLooper())
                maskHandler?.postDelayed({
                    val masked = "****-****-****-" + digits.takeLast(4)
                    cardInput.setText(masked)
                    cardInput.setSelection(masked.length)
                    validateInputs()
                }, 1000)
                cardInput.addTextChangedListener(this)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    /**
     * Sets up the expiry date input field with formatting.
     */
    private fun setupExpiryInput() {
        expiryInput.inputType = InputType.TYPE_CLASS_NUMBER
        expiryInput.filters = arrayOf(InputFilter.LengthFilter(5))
        expiryInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                expiryInput.removeTextChangedListener(this)
                var raw = s.toString().replace("/", "")
                if (raw.length > 4) raw = raw.take(4)
                val formatted = if (raw.length > 2) raw.substring(0, 2) + "/" + raw.substring(2) else raw
                expiryInput.setText(formatted)
                expiryInput.setSelection(formatted.length)
                validateInputs()
                expiryInput.addTextChangedListener(this)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    /**
     * Sets up the CVV input field with masking.
     */
    private fun setupCVVInput() {
        cvvInput.inputType = InputType.TYPE_CLASS_NUMBER
        cvvInput.filters = arrayOf(InputFilter.LengthFilter(3))
        var maskHandler: Handler? = null
        cvvInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                maskHandler?.removeCallbacksAndMessages(null)
                val raw = s.toString().take(3)
                maskHandler = Handler(Looper.getMainLooper())
                maskHandler?.postDelayed({
                    cvvInput.setText("***")
                    cvvInput.setSelection(3)
                    validateInputs()
                }, 1000)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    /**
     * Validates all input fields and enables/disables the confirm button accordingly.
     */
    private fun validateInputs() {
        val cardValid = cardInput.text.toString().replace("-", "").length == 16
        val expiryValid = expiryInput.text.toString().matches(Regex("""\d{2}/\d{2}"""))
        val cvvValid = cvvInput.text.toString() == "***"

        allInputsValid = cardValid && expiryValid && cvvValid
        confirmButton.isEnabled = allInputsValid

        if (allInputsValid) {
            paymentText.text = "Card: ****-****-****-${cardInput.text.takeLast(4)}\nExp: ${expiryInput.text}\nCVV: ***"
        }
    }

    /**
     * Shows the order success dialog with the order number.
     */
    private fun showSuccessDialog() {
        AlertDialog.Builder(this)
            .setTitle("Order Confirmed")
            .setMessage("Thank you! Your order has been placed.\nOrder Number: $lastOrderNumber")
            .setPositiveButton("OK") { _, _ ->
                startActivity(Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
                finish()
            }.setCancelable(false)
            .show()
    }

    /**
     * Calculates the distance between two geographic points in miles.
     *
     * @param lat1 Latitude of point 1
     * @param lon1 Longitude of point 1
     * @param lat2 Latitude of point 2
     * @param lon2 Longitude of point 2
     * @return Distance in miles
     */
    private fun calculateDistanceMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 3958.8 // Earth radius in miles
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Shows a short toast message.
     *
     * @param msg The message to display
     */
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}