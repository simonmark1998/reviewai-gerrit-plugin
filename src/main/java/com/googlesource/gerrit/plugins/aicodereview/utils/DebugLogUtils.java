// Copyright (C) 2024 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.aicodereview.utils;

public class DebugLogUtils {
  private static final int MAX_SUMMARY_CHARS = 2000;

  private DebugLogUtils() {}

  public static String summarize(String value) {
    if (value == null) {
      return "<null>";
    }
    String normalized = value.replace("\r", "\\r").replace("\n", "\\n");
    if (normalized.length() <= MAX_SUMMARY_CHARS) {
      return normalized;
    }
    return normalized.substring(0, MAX_SUMMARY_CHARS)
        + "...<truncated "
        + (normalized.length() - MAX_SUMMARY_CHARS)
        + " chars>";
  }

  public static int length(String value) {
    return value == null ? 0 : value.length();
  }
}
