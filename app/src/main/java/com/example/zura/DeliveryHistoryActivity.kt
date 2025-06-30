package com.example.zura

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

/**
 * This class displays a driver's completed delivery history.
 *
 * This activity shows:
 * - List of all completed deliveries (status = "order completed")
 * - Delivery details including store, address, order numbers, earnings and timestamps
 * - Properly formatted display of each delivery record
 * - Empty state handling when no deliveries exist
 *
 * The activity queries Firestore for completed deliveries associated with the current driver.
 */
class DeliveryHistoryActivity : AppCompatActivity() {

    /** Firestore database instance */
    private val db = FirebaseFirestore.getInstance()

    /** Firebase Authentication instance */
    private val auth = FirebaseAuth.getInstance()

    /** Layout container for displaying delivery history cards */
    private lateinit var historyLayout: LinearLayout

    /** Date formatter for displaying timestamps */
    private val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.UK)

    /**
     * Called when the activity is first created.
     * Sets up the UI and loads delivery history.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_delivery_history)

        // Handle window insets for edge-to-edge display
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.delivery_history_main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        historyLayout = findViewById(R.id.historyContainer)
        loadDeliveryHistory()
    }

    /**
     * Loads the driver's completed delivery history from Firestore.
     *
     * Queries for all orders that:
     * - Have status "order completed"
     * - Were delivered by the current driver
     *
     * Displays each delivery in a card with relevant details.
     * Shows empty state if no deliveries exist.
     */
    private fun loadDeliveryHistory() {
        val driverId = auth.currentUser?.uid ?: return

        db.collection("orders")
            .whereEqualTo("status", "order completed")
            .whereEqualTo("driverId", driverId)
            .get()
            .addOnSuccessListener { docs ->
                historyLayout.removeAllViews()

                // Handle empty state
                if (docs.isEmpty) {
                    val noOrders = TextView(this).apply {
                        text = "No completed deliveries yet."
                        textSize = 16f
                        setTextColor(resources.getColor(android.R.color.white, null))
                        setPadding(16, 32, 16, 16)
                    }
                    historyLayout.addView(noOrders)
                    return@addOnSuccessListener
                }

                // Create a card for each completed delivery
                for (doc in docs) {
                    val store = doc.getString("store") ?: "Unknown Store"
                    val address = doc.getString("address") ?: "Unknown Address"
                    val receiptNumber = doc.getString("receiptNumber") ?: "N/A"
                    val orderNumber = doc.getString("orderNumber") ?: "N/A"
                    val time = doc.getTimestamp("timestamp")?.toDate()
                    val price = doc.getDouble("price") ?: 0.0

                    // Create container for delivery card
                    val container = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(30, 30, 30, 40)
                        setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, 40) }
                    }

                    // Create text view with delivery details
                    val info = TextView(this).apply {
                        text = """
                            Store: $store
                            Delivery Address: $address
                            Order #: $orderNumber
                            Receipt #: $receiptNumber
                            Earned: £${"%.2f".format(price)}
                            Delivered: ${sdf.format(time ?: Date())}
                        """.trimIndent()
                        textSize = 15f
                    }

                    container.addView(info)
                    historyLayout.addView(container)
                }
            }
            .addOnFailureListener {
                Log.e("DeliveryHistory", "Failed to load deliveries", it)
                Toast.makeText(this, "Error loading history.", Toast.LENGTH_SHORT).show()
            }
    }
}