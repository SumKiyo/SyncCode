package com.example.synccode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.synccode.data.AppDatabase
import com.example.synccode.databinding.ActivityMainBinding
import com.example.synccode.ui.VerificationCodeAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: VerificationCodeAdapter
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        db = (application as App).database

        // 配置 RecyclerView
        adapter = VerificationCodeAdapter { code ->
            // 点击验证码 → 复制到剪贴板
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("验证码", code))
            Toast.makeText(this, "验证码 $code 已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(true)
        }

        // 观察数据库变化，实时更新列表
        lifecycleScope.launch {
            db.verificationCodeDao().getAllCodes().collectLatest { codes ->
                adapter.submitList(codes)

                // 更新同步计数
                binding.tvSyncCount.text = codes.size.toString()

                // 空状态切换
                if (codes.isEmpty()) {
                    binding.emptyState.visibility = android.view.View.VISIBLE
                    binding.recyclerView.visibility = android.view.View.GONE
                    binding.tvEmptyHint.visibility = android.view.View.VISIBLE
                } else {
                    binding.emptyState.visibility = android.view.View.GONE
                    binding.recyclerView.visibility = android.view.View.VISIBLE
                    binding.tvEmptyHint.visibility = android.view.View.GONE
                }
            }
        }
    }
}