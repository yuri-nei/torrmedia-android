package ru.yourok.torrserve.ext

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentTransaction
import ru.yourok.torrserve.R

fun FragmentActivity.commitFragment(block: FragmentTransaction.() -> Unit) {
    // Fade through the background, not a cross-fade: the system fade_in/fade_out last 500/400 ms
    // and run at the same time, so transparent screens showed through each other for half a second.
    val transact = supportFragmentManager.beginTransaction()
        .setCustomAnimations(
            R.anim.screen_enter,
            R.anim.screen_exit,
            R.anim.screen_enter,
            R.anim.screen_exit
        )
    block(transact)
    // experimental, to avoid frequent FCs in TSFragment
    transact.commitAllowingStateLoss() //transact.commit()
}

fun FragmentActivity.getLastFragment(): Fragment? {
    if (supportFragmentManager.backStackEntryCount > 0)
        return supportFragmentManager.fragments.last()
    return null
}

fun Fragment.getLastFragment() = requireActivity().getLastFragment()

fun FragmentActivity.clearStackFragment() {
    if (supportFragmentManager.backStackEntryCount > 0)
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
}

fun Fragment.clearStackFragment() = requireActivity().clearStackFragment()

fun FragmentActivity.popBackStackFragment() {
    if (supportFragmentManager.backStackEntryCount > 0)
        supportFragmentManager.popBackStack()
}

fun Fragment.popBackStackFragment() = requireActivity().popBackStackFragment()