package com.craftstudio.launcher.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.movtery.anim.AnimPlayer
import com.movtery.anim.animations.Animations
import com.craftstudio.launcher.R
import com.craftstudio.launcher.databinding.FragmentAboutBinding
import com.craftstudio.launcher.ui.fragment.about.AboutInfoPageFragment
import com.craftstudio.launcher.utils.ZHTools
import com.craftstudio.launcher.utils.stringutils.StringUtils

class AboutFragment : FragmentWithAnim(R.layout.fragment_about) {
    companion object {
        const val TAG: String = "AboutFragment"
    }

    private lateinit var binding: FragmentAboutBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAboutBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initViewPager()

        binding.apply {

            // App version info
            appInfo.text = StringUtils.insertNewline(
                StringUtils.insertSpace(
                    getString(R.string.about_version_name),
                    ZHTools.getVersionName()
                ),
                StringUtils.insertSpace(
                    getString(R.string.about_version_code),
                    ZHTools.getVersionCode()
                ),
                StringUtils.insertSpace(
                    getString(R.string.about_last_update_time),
                    ZHTools.getLastUpdateTime(requireContext())
                ),
                StringUtils.insertSpace(
                    getString(R.string.about_version_status),
                    ZHTools.getVersionStatus(requireContext())
                )
            )

            // Copy on click
            appInfo.setOnClickListener {
                StringUtils.copyText(
                    "text",
                    appInfo.text.toString(),
                    requireContext()
                )
            }

            // Back button
            returnButton.setOnClickListener {
                ZHTools.onBackPressed(requireActivity())
            }

            // ✅ Sponsor button hide
            supportDevelopment.visibility = View.GONE
        }
    }

    private fun initViewPager() {
        binding.infoViewPager.apply {
            adapter = ViewPagerAdapter(requireActivity(), this)
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            offscreenPageLimit = 1
            // ✅ Swipe disable
            isUserInputEnabled = false
        }
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer
            .apply(AnimPlayer.Entry(binding.infoViewPager, Animations.BounceInDown))
            .apply(AnimPlayer.Entry(binding.operateLayout, Animations.BounceInLeft))
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        animPlayer.apply(AnimPlayer.Entry(binding.infoViewPager, Animations.FadeOutUp))
        animPlayer.apply(AnimPlayer.Entry(binding.operateLayout, Animations.FadeOutRight))
    }

    // ✅ Sirf 1 page - Sponsor page completely removed
    private class ViewPagerAdapter(
        fragmentActivity: FragmentActivity,
        private val viewPager: ViewPager2
    ) : FragmentStateAdapter(fragmentActivity) {

        override fun getItemCount(): Int = 1

        override fun createFragment(position: Int): Fragment {
            return AboutInfoPageFragment(viewPager)
        }
    }
}
