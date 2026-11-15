#!/usr/bin/env python3
"""Catch the dex argument-count overflow before it reaches an APK.

dex encodes a call as either 35c (up to five arguments, packed into
nibbles) or 3rc (a register window, with the number of words in one byte
of the instruction). The byte holds 255, so a call that needs 256 words
cannot be written down: the count wraps to zero and Android's verifier
reads that as "no arguments at all" and rejects the class outright.

    VerifyError: Verifier rejected class com.music.bitchord.MainActivityKt
    [0xFFFFFFFF] invalid arg count (0) in invoke-direct/range

The JVM allows a constructor 254 arguments long (255 slots with the
object itself), and the call to it needs 255 words, which is exactly
what dex can carry. One more argument and the call cannot be written at
all. Kotlin does not complain: the limit is dex's, and dex is not the
compiler.

So: every constructor in the compiled classes is counted, and anything
wider than 254 arguments fails the build.

    python3 tools/check_constructor_widths.py app/build/tmp/kotlin-classes/devRelease

A class that cannot be parsed is a failure too, not a silent pass: a
guard that quietly skips the classes it cannot read is not a guard.
"""
import glob
import os
import struct
import sys


# Constant pool entry sizes, tag byte included. Long and double take two
# slots in the pool, which is why the reader below advances the index twice
# for them.
CONSTANT_SIZES = {
    3: 5,    # Integer
    4: 5,    # Float
    5: 9,    # Long
    6: 9,    # Double
    7: 3,    # Class
    8: 3,    # String
    9: 5,    # Fieldref
    10: 5,   # Methodref
    11: 5,   # InterfaceMethodref
    12: 5,   # NameAndType
    15: 4,   # MethodHandle
    16: 3,   # MethodType
    17: 5,   # Dynamic
    18: 5,   # InvokeDynamic
    19: 3,   # Module
    20: 3,   # Package
}


def constant_pool(data):
    """The pool's entries, by index. Unknown tags are a hard error."""
    count = struct.unpack_from(">H", data, 8)[0]
    off = 10
    pool = {}
    i = 1
    while i < count:
        tag = data[off]
        if tag == 1:
            length = struct.unpack_from(">H", data, off + 1)[0]
            pool[i] = data[off + 3: off + 3 + length].decode("utf-8", "replace")
            off += 3 + length
        elif tag in CONSTANT_SIZES:
            size = CONSTANT_SIZES[tag]
            if tag in (7, 8, 16):
                pool[i] = struct.unpack_from(">H", data, off + 1)[0]
            elif tag in (9, 10, 11, 12, 17, 18):
                pool[i] = struct.unpack_from(">H", data, off + 1)[0]
            off += size
        else:
            raise ValueError("unknown constant pool tag %d" % tag)
        if tag in (5, 6):
            i += 2
        else:
            i += 1
    return pool, off


def code_line(info, pool):
    """The first source line in a method's Code attribute, when it has one."""
    off = 4  # max_stack, max_locals
    code_length = struct.unpack_from(">I", info, off)[0]
    off += 4 + code_length
    exception_count = struct.unpack_from(">H", info, off)[0]
    off += 2 + 8 * exception_count
    attribute_count = struct.unpack_from(">H", info, off)[0]
    off += 2
    best = None
    for _ in range(attribute_count):
        name_index = struct.unpack_from(">H", info, off)[0]
        length = struct.unpack_from(">I", info, off + 2)[0]
        body = info[off + 6: off + 6 + length]
        off += 6 + length
        if pool.get(name_index) != "LineNumberTable":
            continue
        entries = struct.unpack_from(">H", body, 0)[0]
        for index in range(entries):
            line = struct.unpack_from(">H", body, 4 + index * 4)[0]
            if best is None or line < best:
                best = line
    return best


def methods(data, pool):
    """Every method with the source line its code starts on, when known."""
    count = struct.unpack_from(">H", data, 8)[0]
    off = 10
    i = 1
    while i < count:
        tag = data[off]
        if tag == 1:
            off += 3 + struct.unpack_from(">H", data, off + 1)[0]
        elif tag in CONSTANT_SIZES:
            off += CONSTANT_SIZES[tag]
        else:
            raise ValueError("unknown constant pool tag %d" % tag)
        if tag in (5, 6):
            i += 2
        else:
            i += 1

    off += 6  # access_flags, this_class, super_class
    interfaces = struct.unpack_from(">H", data, off)[0]
    off += 2 + 2 * interfaces
    fields = struct.unpack_from(">H", data, off)[0]
    off += 2
    for _ in range(fields):
        off += 6
        attributes = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(attributes):
            off += 2
            length = struct.unpack_from(">I", data, off)[0]
            off += 4 + length

    found = []
    count = struct.unpack_from(">H", data, off)[0]
    off += 2
    for _ in range(count):
        _, name_index, descriptor_index = struct.unpack_from(">HHH", data, off)
        off += 6
        attributes = struct.unpack_from(">H", data, off)[0]
        off += 2
        line = None
        for _ in range(attributes):
            attribute_name = struct.unpack_from(">H", data, off)[0]
            length = struct.unpack_from(">I", data, off + 2)[0]
            info = data[off + 6: off + 6 + length]
            off += 6 + length
            if pool.get(attribute_name) == "Code":
                line = code_line(info, pool)
        found.append((pool.get(name_index), pool.get(descriptor_index), line))
    return found


def argument_words(descriptor):
    inside = descriptor[descriptor.index("(") + 1:descriptor.rindex(")")]
    words = 0
    i = 0
    while i < len(inside):
        char = inside[i]
        if char == "L":
            words += 1
            i = inside.index(";", i) + 1
        elif char == "[":
            i += 1
        elif char in "JD":
            words += 2
            i += 1
        else:
            words += 1
            i += 1
    return words


def main(roots):
    limit = 254
    bad = []
    unreadable = []
    widest = []
    for root in roots:
        for path in glob.glob(os.path.join(root, "**", "*.class"), recursive=True):
            data = open(path, "rb").read()
            if data[:4] != b"\xca\xfe\xba\xbe":
                continue
            try:
                pool, _ = constant_pool(data)
                for name, descriptor, line in methods(data, pool):
                    if name != "<init>" or descriptor is None:
                        continue
                    words = argument_words(descriptor)
                    widest.append((words, path, line))
                    if words > limit:
                        bad.append((words, path, line))
            except (ValueError, KeyError, IndexError, struct.error) as failure:
                unreadable.append((path, failure))
    for words, path, line in sorted(bad, reverse=True):
        where = "" if line is None else " (source line %d)" % line
        print("%s: constructor takes %d arguments%s" % (path, words, where))
    if unreadable:
        print("")
        print("%d class file(s) could not be read, so their constructors were"
              % len(unreadable))
        print("not counted. A guard that skips what it cannot read is not a guard.")
        for path, failure in sorted(unreadable)[:20]:
            print("  %s: %s" % (path, failure))
        return 1
    if bad:
        print(
            "\n%d constructor(s) wider than dex can call. A call to a "
            "constructor with N arguments needs N+1 registers, and dex "
            "keeps that count in one byte (255). Split the captures: a "
            "lambda that holds more than 254 of them cannot be called." % len(bad)
        )
        return 1
    widest.sort(reverse=True)
    if widest:
        words, path, line = widest[0]
        where = "" if line is None else " (source line %d)" % line
        print("constructors: all within dex's argument limit (widest %d: %s%s)"
              % (words, os.path.basename(path), where))
    else:
        print("constructors: all within dex's argument limit")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["."]))
