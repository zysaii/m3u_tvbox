package com.lizongying.mytv

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.lizongying.mytv.databinding.DialogM3uSettingBinding
import com.lizongying.mytv.models.M3UConfig
import kotlinx.coroutines.launch
import java.util.UUID

class M3USettingFragment : DialogFragment() {

    private var _binding: DialogM3uSettingBinding? = null
    private val binding get() = _binding!!

    private val m3uList = mutableListOf<M3UConfig>()
    private lateinit var adapter: M3UAdapter

    var onM3UConfigChangedListener: (() -> Unit)? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val dm = resources.displayMetrics
            val dialogWidth = (dm.widthPixels * 0.85).toInt().coerceAtMost((720 * dm.density).toInt())
            val dialogHeight = (dm.heightPixels * 0.88).toInt()
            setLayout(dialogWidth, dialogHeight)
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogM3uSettingBinding.inflate(inflater, container, false)

        m3uList.clear()
        m3uList.addAll(M3UManager.getM3UList())

        adapter = M3UAdapter(requireContext(), m3uList)
        binding.lvM3u.itemsCanFocus = true
        binding.lvM3u.adapter = adapter

        binding.btnAddM3u.setOnClickListener {
            showEditDialog(null)
        }

        binding.btnCloseM3u.setOnClickListener {
            dismiss()
        }

        return binding.root
    }

    private fun reloadAndNotify() {
        M3UManager.saveM3UList(m3uList)
        adapter.notifyDataSetChanged()

        // 异步下载解析启用的源并更新频道
        binding.layoutLoading.visibility = View.VISIBLE
        binding.tvLoadingText.text = "正在下载并解析直播源..."
        lifecycleScope.launch {
            try {
                val resultMsg = M3UManager.loadAndApplyEnabledM3U()
                Toast.makeText(context, resultMsg, Toast.LENGTH_LONG).show()
                onM3UConfigChangedListener?.invoke()
            } catch (e: Exception) {
                Toast.makeText(context, "更新异常: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (_binding != null) {
                    binding.layoutLoading.visibility = View.GONE
                }
            }
        }
    }

    private fun showEditDialog(config: M3UConfig?) {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_m3u_edit, null)
        val tvTitle = view.findViewById<TextView>(R.id.tv_dialog_title)
        val etName = view.findViewById<EditText>(R.id.et_m3u_name)
        val etUrl = view.findViewById<EditText>(R.id.et_m3u_url)

        val isEdit = config != null
        if (isEdit) {
            tvTitle.text = "编辑 M3U 直播源"
            etName.setText(config!!.name)
            etUrl.setText(config.url)
        } else {
            tvTitle.text = "添加 M3U 直播源"
            etName.setText("自定义源")
            etUrl.setText(M3UManager.DEFAULT_M3U_URL)
        }

        AlertDialog.Builder(context)
            .setView(view)
            .setPositiveButton("保存") { dialog, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(context, "M3U 地址不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (isEdit) {
                    config!!.name = if (name.isEmpty()) "未命名" else name
                    config.url = url
                } else {
                    val newConfig = M3UConfig(
                        id = UUID.randomUUID().toString(),
                        name = if (name.isEmpty()) "未命名" else name,
                        url = url,
                        enabled = true
                    )
                    m3uList.add(newConfig)
                }
                reloadAndNotify()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class M3UAdapter(
        private val context: Context,
        private val list: MutableList<M3UConfig>
    ) : BaseAdapter() {

        override fun getCount(): Int = list.size

        override fun getItem(position: Int): M3UConfig = list[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_m3u, parent, false)
            val item = getItem(position)

            val layoutToggle = view.findViewById<View>(R.id.layout_m3u_toggle)
            val cbEnable = view.findViewById<CheckBox>(R.id.cb_m3u_enable)
            val tvStatus = view.findViewById<TextView>(R.id.tv_m3u_status)
            val tvName = view.findViewById<TextView>(R.id.tv_m3u_name)
            val tvUrl = view.findViewById<TextView>(R.id.tv_m3u_url)
            val btnEdit = view.findViewById<Button>(R.id.btn_m3u_edit)
            val btnDelete = view.findViewById<Button>(R.id.btn_m3u_delete)

            tvName.text = item.name
            tvUrl.text = item.url
            cbEnable.isChecked = item.enabled

            if (item.enabled) {
                tvStatus.text = "[已启用]"
                tvStatus.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
            } else {
                tvStatus.text = "[已停用]"
                tvStatus.setTextColor(android.graphics.Color.parseColor("#78909C"))
            }

            if (item.isBuiltin) {
                btnEdit.visibility = View.GONE
                btnDelete.visibility = View.GONE
            } else {
                btnEdit.visibility = View.VISIBLE
                btnDelete.visibility = View.VISIBLE
            }

            // 点击或遥控器在左侧区域按OK键切换启用状态
            layoutToggle.setOnClickListener {
                item.enabled = !item.enabled
                cbEnable.isChecked = item.enabled
                reloadAndNotify()
            }

            btnEdit.setOnClickListener {
                showEditDialog(item)
            }

            btnDelete.setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle("确认删除")
                    .setMessage("确定删除直播源「${item.name}」吗？")
                    .setPositiveButton("删除") { dialog, _ ->
                        list.removeAt(position)
                        reloadAndNotify()
                        dialog.dismiss()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            return view
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "M3USettingFragment"
    }
}

