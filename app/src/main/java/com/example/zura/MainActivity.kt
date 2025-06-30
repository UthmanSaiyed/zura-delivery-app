package com.example.zura

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * This class displays regular users after successful login.
 *
 * This activity serves as the user dashboard and provides:
 * - User greeting with name and delivery PIN
 * - Recent order display
 * - Navigation to request deliveries, view history, track orders, and profile
 * - Real-time order status updates with notifications
 * - Logout functionality
 *
 * The activity handles notification channel creation and permission requests
 * for Android 13+ devices.
 */
class MainActivity : AppCompatActivity() {

    /** Firebase Authentication instance */
    private val auth = FirebaseAuth.getInstance()

    /** Firestore database instance */
    private val db = FirebaseFirestore.getInstance()

    /** TextView displaying user greeting */
    private lateinit var userTextView: TextView

    /** TextView displaying delivery PIN */
    private lateinit var pinTextView: TextView

    /** TextView displaying recent orders */
    private lateinit var orderList: TextView

    /** Activity launcher for requesting deliveries */
    private lateinit var requestDeliveryLauncher: ActivityResultLauncher<Intent>

    /** Latest order snapshot for tracking status changes */
    private var latestOrderSnapshot: DocumentSnapshot? = null

    /** Notification channel ID for order updates */
    private val CHANNEL_ID = "order_updates_channel"

    /**
     * Called when the activity is first created.
     * Sets up the UI, loads user information, and initializes notification systems.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Handle window insets for edge-to-edge display
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Initialize UI components
        userTextView = findViewById(R.id.userText)
        pinTextView = findViewById(R.id.deliveryPinText)
        orderList = findViewById(R.id.orderList)

        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "Not logged in.", Toast.LENGTH_SHORT).show()
            orderList.text = "Please log in to view orders."
            return
        }

        // Load and display user information
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener {
                val name = it.getString("fullName") ?: "User"
                val pin = it.getString("deliveryPin") ?: "N/A"
                userTextView.text = "Welcome back, ${name.split(" ").firstOrNull() ?: name}!"
                pinTextView.text = "Your Delivery PIN: $pin"
            }

        // Set up activity launcher for requesting deliveries
        requestDeliveryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                loadRecentOrders(user.uid)
                Toast.makeText(this, "Order placed successfully!", Toast.LENGTH_SHORT).show()
            }
        }

        // Set up navigation buttons
        findViewById<Button>(R.id.requestDeliveryBtn).setOnClickListener {
            requestDeliveryLauncher.launch(Intent(this, RequestDeliveryActivity::class.java))
        }

        findViewById<Button>(R.id.viewHistoryBtn).setOnClickListener {
            startActivity(Intent(this, OrderHistoryActivity::class.java))
        }

        findViewById<Button>(R.id.profileBtn).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        findViewById<Button>(R.id.trackOrderBtn).setOnClickListener {
            startActivity(Intent(this, TrackOrderActivity::class.java))
        }

        // Set up logout button with confirmation dialog
        findViewById<Button>(R.id.logoutBtn).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to log out?")
                .setNegativeButton("Yes") { _, _ ->
                    auth.signOut()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
                .setPositiveButton("No", null)
                .show()
        }

        // Load data and set up notification systems
        loadRecentOrders(user.uid)
        createNotificationChannel()
        requestNotificationPermission()
        listenForOrderUpdates(user.uid)
    }

    /**
     * Called when the activity resumes.
     * Refreshes the recent orders list.
     */
    override fun onResume() {
        super.onResume()
        auth.currentUser?.uid?.let { loadRecentOrders(it) }
    }

    /**
     * Loads and displays the user's most recent orders.
     *
     * @param userId The ID of the current user
     */
    private fun loadRecentOrders(userId: String) {
        db.collection("orders")
            .whereEqualTo("userId", userId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(2)
            .get()
            .addOnSuccessListener { snapshots ->
                if (snapshots.isEmpty) {
                    orderList.text = "You're all caught up!"
                } else {
                    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.UK)
                    orderList.text = snapshots.joinToString("\n\n") { doc ->
                        val store = doc.getString("store") ?: "Unknown store"
                        val address = doc.getString("address") ?: "No address"
                        val timeStr = doc.getTimestamp("timestamp")?.toDate()?.let { sdf.format(it) } ?: "Unknown time"
                        val orderNumber = doc.getString("orderNumber") ?: "N/A"
                        val status = doc.getString("status")?.replaceFirstChar { it.uppercase() } ?: "Pending"

                        """
                          Store: $store
                          Order #: $orderNumber
                          Address: $address
                          Ordered: $timeStr
                          Status: $status
                        """.trimIndent()
                    }
                }
            }
            .addOnFailureListener {
                Log.e("MainActivity", "Error fetching orders", it)
                orderList.text = "Failed to load orders: ${it.message}"
            }
    }

    /**
     * Listens for real-time updates to the user's orders.
     * Sends notifications when order status changes.
     *
     * @param userId The ID of the current user
     */
    private fun listenForOrderUpdates(userId: String) {
        db.collection("orders")
            .whereEqualTo("userId", userId)
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null || snapshots.isEmpty) return@addSnapshotListener

                val doc = snapshots.documents.maxByOrNull { it.getTimestamp("timestamp")?.toDate()?.time ?: 0 } ?: return@addSnapshotListener
                val prevStatus = latestOrderSnapshot?.getString("status")
                val newStatus = doc.getString("status")

                if (prevStatus != null && newStatus != null && prevStatus != newStatus && newStatus.lowercase() != "pending") {
                    sendNotification(
                        "Order Update",
                        "Your order status changed to: ${newStatus.replaceFirstChar { it.uppercase() }}"
                    )
                }

                latestOrderSnapshot = doc
            }
    }

    /**
     * Creates and displays a notification to the user.
     *
     * @param title The title of the notification
     * @param message The content text of the notification
     */
    private fun sendNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_credit_card_24)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        with(NotificationManagerCompat.from(this)) {
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notify(System.currentTimeMillis().toInt(), notification)
            }
        }
    }

    /**
     * Creates the notification channel required for Android 8.0+.
     * This channel is used for all order update notifications.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Order Updates"
            val descriptionText = "Notifies when your order status changes"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Requests notification permission for Android 13+ devices.
     * This is required to display notifications on newer Android versions.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
}