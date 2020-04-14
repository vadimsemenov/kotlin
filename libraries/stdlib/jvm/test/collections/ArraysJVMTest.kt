/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package test.collections

import java.util.Collections
import kotlin.test.*


class ArraysJVMTest {

    @Suppress("HasPlatformType", "UNCHECKED_CAST")
    fun <T> platformNull() = Collections.singletonList(null as T).first()

    @Test
    fun contentEquals() {
        assertTrue(platformNull<IntArray>() contentEquals null)
        assertTrue(null contentEquals platformNull<LongArray>())

        assertFalse(platformNull<Array<String>>() contentEquals emptyArray<String>())
        assertFalse(arrayOf("a", "b") contentEquals platformNull<Array<String>>())

        assertFalse(platformNull<UShortArray>() contentEquals ushortArrayOf())
        assertFalse(ubyteArrayOf() contentEquals platformNull<UByteArray>())
    }

    @Test
    fun contentHashCode() {
        assertEquals(0, platformNull<Array<Int>>().contentHashCode())
        assertEquals(0, platformNull<CharArray>().contentHashCode())
        assertEquals(0, platformNull<ShortArray>().contentHashCode())
        assertEquals(0, platformNull<BooleanArray>().contentHashCode())
        assertEquals(0, platformNull<UByteArray>().contentHashCode())
        assertEquals(0, platformNull<UIntArray>().contentHashCode())
    }

    @Test
    fun contentToString() {
        assertEquals("null", platformNull<Array<String>>().contentToString())
        assertEquals("null", platformNull<CharArray>().contentToString())
        assertEquals("null", platformNull<DoubleArray>().contentToString())
        assertEquals("null", platformNull<FloatArray>().contentToString())
        assertEquals("null", platformNull<ULongArray>().contentToString())
    }

    @Test
    fun contentDeepEquals() {
        assertFalse(platformNull<Array<String>>() contentDeepEquals emptyArray<String>())
        assertFalse(arrayOf("a", "b") contentDeepEquals platformNull<Array<String>>())
    }

    @Test
    fun contentDeepHashCode() {
        assertEquals(0, platformNull<Array<Int>>().contentDeepHashCode())
    }

    @Test
    fun contentDeepToString() {
        assertEquals("null", platformNull<Array<String>>().contentDeepToString())
    }

    @Test
    fun sortDescendingRangeInPlace() {

        fun <TArray, T : Comparable<T>> doTest(
            build: Iterable<Int>.() -> TArray,
            sortDescending: TArray.(fromIndex: Int, toIndex: Int) -> Unit,
            snapshot: TArray.() -> List<T>
        ) {
            val arrays = (0..7).map { n -> n to (-2 until n - 2).build() }
            for ((size, array) in arrays) {
                for (fromIndex in 0 until size) {
                    for (toIndex in fromIndex until size) {
                        val original = array.snapshot().toMutableList()
                        array.sortDescending(fromIndex, toIndex)
                        val reversed = array.snapshot()
                        assertEquals(original.apply { subList(fromIndex, toIndex).sortDescending() }, reversed)
                    }
                }

                assertFailsWith<IndexOutOfBoundsException> { array.sortDescending(-1, size) }
                assertFailsWith<IndexOutOfBoundsException> { array.sortDescending(0, size + 1) }
                assertFailsWith<IllegalArgumentException> { array.sortDescending(1, 0) }
            }
        }

        doTest(build = { map {it.toString()}.toTypedArray() }, sortDescending = { from, to -> sortDescending(from, to) }, snapshot = { toList() })
        doTest(build = { map {it.toString()}.toTypedArray() as Array<out String> }, sortDescending = { from, to -> sortDescending(from, to) }, snapshot = { toList() })

        doTest(build = { map {it}.toIntArray() }, sortDescending = { from, to -> sortDescending(from, to) }, snapshot = { toList() })
        doTest(build = { map {it.toLong()}.toLongArray() }, sortDescending = { from, to -> sortDescending(from, to) }, snapshot = { toList() })
        doTest(build = { map {it.toByte()}.toByteArray() }, sortDescending = { from, to -> sortDescending(from, to) }, snapshot = { toList() })
        doTest(build = { map {it.toShort()}.toShortArray() }, sortDescending = { from, to -> sortDescending(from, to) }, snapshot = { toList() })
        doTest(build = { map {it.toFloat()}.toFloatArray() }, sortDescending = { from, to -> sortDescending(from, to) }, snapshot = { toList() })
        doTest(build = { map {it.toDouble()}.toDoubleArray() }, sortDescending = { from, to -> sortDescending(from, to) }, snapshot = { toList() })
        doTest(build = { map {'a' + it}.toCharArray() }, sortDescending = { from, to -> sortDescending(from, to) }, snapshot = { toList() })
        doTest(build = { map {it.toUInt()}.toUIntArray() }, sortDescending = { from, to -> sortDescending(from, to) }, snapshot = { toList() })
        doTest(build = { map {it.toULong()}.toULongArray() }, sortDescending = { from, to -> sortDescending(from, to) }, snapshot = { toList() })
        doTest(build = { map {it.toUByte()}.toUByteArray() }, sortDescending = { from, to -> sortDescending(from, to) }, snapshot = { toList() })
        doTest(build = { map {it.toUShort()}.toUShortArray() }, sortDescending = { from, to -> sortDescending(from, to) }, snapshot = { toList() })
    }

    @Test
    fun sortDescendingRangeInPlace_Objects() {
        val first1 = "first"
        val first2 = first1.toCharArray().concatToString()
        val first3 = first1.toCharArray().concatToString()
        val second1 = "second"
        val second2 = second1.toCharArray().concatToString()
        val third1 = "third"

        assertEquals(first1, first2)
        assertEquals(first1, first3)
        assertNotSame(first1, first2)
        assertNotSame(first1, first3)
        assertNotSame(first2, first3)

        assertEquals(second1, second2)
        assertNotSame(second1, second2)

        val original = arrayOf(first3, third1, second2, first2, first1, second1)
        original.copyOf().apply { sortDescending(1, 5) }.forEachIndexed { i, e ->
            assertSame(original[i], e)
        }

        val sorted = arrayOf(third1, second2, second1, first3, first2, first1)
        original.apply { sortDescending(0, 6) }.forEachIndexed { i, e ->
            assertSame(sorted[i], e)
        }
    }
}