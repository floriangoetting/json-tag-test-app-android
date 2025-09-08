package com.floriangoetting.jsontagtestapp.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.floriangoetting.jsontagtestapp.base.BaseFragment
import com.floriangoetting.jsontagtestapp.MainActivity
import com.floriangoetting.jsontagtestapp.Tracker
import com.floriangoetting.jsontagtestapp.databinding.FragmentDashboardBinding

class DashboardFragment : BaseFragment() {

    private var _binding: FragmentDashboardBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val dashboardViewModel =
            ViewModelProvider(this).get(DashboardViewModel::class.java)

        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val textView: TextView = binding.textDashboard
        dashboardViewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }

        // Add to Cart button listener
        binding.btnAddToCart.setOnClickListener {
            val mainActivity = requireActivity() as? MainActivity
            val tracker = mainActivity?.tracker

            val fragmentName = this::class.simpleName ?: "UnknownFragment"

            val pageData = mapOf(
                "name" to fragmentName,
                "type" to "category"
            )

            val productData = mapOf(
                "id" to "12345",
                "name" to "Test Product Name",
                "hierarchy" to listOf(
                    "Category 1", "Category 2", "Category 3"
                ),
                "price" to 19.99,
                "quantity" to 1,
                "discount_offer" to "regular",
                "seller" to "marketplace"
            )

            val cartData = mapOf(
                "id" to "123456"
            )

            val clickData = mapOf(
                "name" to "add_to_cart_click",
                "component" to "productlist",
                "trigger_type" to "trigger",
                "details" to "add to cart icon",
                "page" to pageData
            )

            val eventData = mapOf(
                "page" to pageData,
                "products" to listOf(productData),
                "cart" to cartData,
                "click" to clickData
            )

            tracker?.trackEvent("add_to_cart", Tracker.EventType.CALLBACK, eventData)
        }

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}