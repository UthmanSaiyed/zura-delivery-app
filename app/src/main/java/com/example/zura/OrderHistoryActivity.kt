package com.example.zura

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

/**
 * This activity is for displaying a user's order history.
 *
 * This activity provides:
 * - List of all past orders sorted by timestamp (newest first)
 * - Detailed order information including store, address, notes, and status
 * - Ability to cancel pending orders
 * - Empty state handling when no orders exist
 *
 * The activity queries Firestore for orders associated with the current user.
 */
class OrderHistoryActivity : AppCompatActivity() {

    /** Firestore database instance */
    private val db = FirebaseFirestore.getInstance()

    /** Firebase Authentication instance */
    private val auth = FirebaseAuth.getInstance()

    /** Layout container for displaying order cards */
    private lateinit var ordersLayout: LinearLayout

    /** Date formatter for displaying timestamps */
    private val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.UK)

    /**
     * Called when the activity is first created.
     * Sets up the UI and loads order history.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_history)

        // Handle window insets for edge-to-edge display
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        ordersLayout = findViewById(R.id.ordersLayout)
        loadOrderHistory()
    }

    /**
     * Loads the user's order history from Firestore.
     *
     * Queries for all orders that:
     * - Belong to the current user
     * - Sorted by timestamp (newest first)
     *
     * Displays each order in a card with relevant details.
     * Shows empty state if no orders exist.
     */
    private fun loadOrderHistory() {
        val userId = auth.currentUser?.uid ?: return

        db.collection("orders")
            .whereEqualTo("userId", userId)
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { docs ->
                ordersLayout.removeAllViews()

                // Handle empty state
                if (docs.isEmpty) {
                    val noOrders = TextView(this).apply {
                        text = "No past orders found."
                        textSize = 16f
                        setTextColor(resources.getColor(android.R.color.white, null))
                        setPadding(16, 32, 16, 16)
                    }
                    ordersLayout.addView(noOrders)
                    return@addOnSuccessListener
                }

                // Create a card for each order
                for (doc in docs) {
                    val store = doc.getString("store") ?: "Unknown Store"
                    val address = doc.getString("address") ?: "Unknown Address"
                    val note = doc.getString("note") ?: "No note"
                    val receiptNumber = doc.getString("receiptNumber") ?: "N/A"
                    val orderNumber = doc.getString("orderNumber") ?: "N/A"
                    val time = doc.getTimestamp("timestamp")?.toDate()
                    val status = doc.getString("status") ?: "pending"
                    val orderId = doc.id
                    val price = doc.getDouble("price") ?: 0.0
                    val formattedPrice = String.format("£%.2f", price)

                    // Create container for order card
                    val container = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(30, 30, 30, 40)
                        setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, 40) }
                    }

                    // Create text view with order details
                    val info = TextView(this).apply {
                        text = """
                            Store: $store
                            Address: $address
                            Order #: $orderNumber
                            Delivery Note: $note
                            Receipt Number: $receiptNumber
                            Price: $formattedPrice
                            Ordered: ${sdf.format(time ?: Date())}
                            Status: ${status.replaceFirstChar { it.uppercase() }}
                        """.trimIndent()
                        textSize = 15f
                    }

                    // Create cancel button (only visible for pending orders)
                    val cancelBtn = Button(this).apply {
                        text = "Cancel Order"
                        visibility = if (status == "pending") View.VISIBLE else View.GONE
                        setOnClickListener {
                            showCancelConfirmation(orderId)
                        }
                    }

                    container.addView(info)
                    container.addView(cancelBtn)
                    ordersLayout.addView(container)
                }
            }
            .addOnFailureListener {
                Log.e("OrderHistory", "Failed to load orders", it)
                Toast.makeText(this, "Error loading orders.", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Shows a confirmation dialog for order cancellation.
     *
     * @param orderId The ID of the order to cancel
     */
    private fun showCancelConfirmation(orderId: String) {
        AlertDialog.Builder(this)
            .setTitle("Cancel Order")
            .setMessage("Are you sure you want to cancel this order?")
            .setNegativeButton("Yes") { _, _ -> cancelOrder(orderId) }
            .setPositiveButton("No", null)
            .show()
    }

    /**
     * Cancels the specified order if it's still pending.
     *
     * @param orderId The ID of the order to cancel
     */
    private fun cancelOrder(orderId: String) {
        db.collection("orders").document(orderId).get()
            .addOnSuccessListener { doc ->
                val status = doc.getString("status") ?: "pending"
                if (status != "pending") {
                    Toast.makeText(this, "Order cannot be cancelled.", Toast.LENGTH_SHORT).show()
                } else {
                    // Delete the pending order
                    db.collection("orders").document(orderId)
                        .delete()
                        .addOnSuccessListener {
                            Toast.makeText(this, "Order cancelled.", Toast.LENGTH_SHORT).show()
                            loadOrderHistory() // Refresh the list
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Failed to cancel order.", Toast.LENGTH_SHORT).show()
                        }
                }
            }
    }
}