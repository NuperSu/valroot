package io.valroot.core

/** This map's entries in ascending key order, as a map that keeps that order on every platform. */
fun <K : Comparable<K>, V> Map<K, V>.sortedByKeys(): Map<K, V> = entries.sortedBy { it.key }.associate { it.key to it.value }
