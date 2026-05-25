package moe.fuqiuluo.portal.ui.cadence

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import moe.fuqiuluo.portal.R
import moe.fuqiuluo.portal.android.root.ShellUtils
import moe.fuqiuluo.portal.android.window.OverlayUtils
import moe.fuqiuluo.portal.databinding.FragmentCadenceMockBinding
import moe.fuqiuluo.portal.ext.cadenceFloatingEnabled
import moe.fuqiuluo.portal.ext.cadenceMax
import moe.fuqiuluo.portal.ext.cadenceMin
import moe.fuqiuluo.portal.ext.cadenceMockEnabled
import moe.fuqiuluo.portal.ext.hookSensor

class CadenceMockFragment : Fragment() {
    private var _binding: FragmentCadenceMockBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCadenceMockBinding.inflate(inflater, container, false)
        val context = requireContext()

        val minCadence = context.cadenceMin.coerceIn(140f, 220f)
        val maxCadence = context.cadenceMax.coerceIn(140f, 220f)
        binding.cadenceRangeSlider.setValues(
            kotlin.math.min(minCadence, maxCadence),
            kotlin.math.max(minCadence, maxCadence)
        )
        updateRangeText()

        binding.cadenceRangeSlider.addOnChangeListener { slider, _, _ ->
            val values = slider.values
            context.cadenceMin = values[0]
            context.cadenceMax = values[1]
            updateRangeText()
            if (context.cadenceMockEnabled) {
                CadenceBroadcasts.broadcastCurrentState(context, silent = true)
            }
        }

        binding.cadenceSwitch.isChecked = context.cadenceMockEnabled
        binding.cadenceSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.cadenceMockEnabled = isChecked
            context.hookSensor = isChecked
            CadenceBroadcasts.broadcastCurrentState(context)
            updateStatusText()
            showToast(if (isChecked) "步频模拟已开启" else "步频模拟已关闭")
        }

        binding.floatingButton.setOnClickListener {
            if (context.cadenceFloatingEnabled) {
                stopFloatingService()
            } else {
                startFloatingService()
            }
        }

        binding.syncButton.setOnClickListener {
            CadenceBroadcasts.broadcastCurrentState(context)
            showToast("已同步到目标应用")
        }

        updateStatusText()
        updateFloatingButton()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        updateFloatingButton()
    }

    private fun startFloatingService() {
        val context = requireContext()
        if (!ensureOverlayPermission(context)) return

        context.startService(Intent(context, CadenceFloatingService::class.java))
        context.cadenceFloatingEnabled = true
        updateFloatingButton()
        showToast("悬浮窗已启动")
    }

    private fun stopFloatingService() {
        val context = requireContext()
        context.stopService(Intent(context, CadenceFloatingService::class.java))
        context.cadenceFloatingEnabled = false
        updateFloatingButton()
        showToast("悬浮窗已关闭")
    }

    private fun ensureOverlayPermission(context: Context): Boolean {
        if (OverlayUtils.hasOverlayPermissions(context)) return true

        grantOverlayPermissionViaRoot(context)
        if (OverlayUtils.hasOverlayPermissions(context)) return true

        showToast("请授权悬浮窗权限")
        runCatching {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ))
        }
        return false
    }

    private fun grantOverlayPermissionViaRoot(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!ShellUtils.hasRoot()) return

        ShellUtils.executeCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
    }

    private fun updateRangeText() {
        val values = binding.cadenceRangeSlider.values
        binding.cadenceValue.text = "%d - %d 步/分钟".format(values[0].toInt(), values[1].toInt())
    }

    private fun updateStatusText() {
        val context = requireContext()
        val enabled = context.cadenceMockEnabled
        binding.statusValue.text = if (enabled) "运行中" else "已停止"
        binding.statusValue.setTextColor(
            ContextCompat.getColor(context, if (enabled) R.color.portal_mock_on else R.color.grey500)
        )
    }

    private fun updateFloatingButton() {
        if (_binding == null) return
        val context = requireContext()
        binding.floatingButton.text = if (context.cadenceFloatingEnabled) "关闭悬浮窗" else "启动悬浮窗"
        binding.floatingButton.setIconResource(
            if (context.cadenceFloatingEnabled) R.drawable.baseline_stop_24 else R.drawable.rounded_play_arrow_24
        )
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
