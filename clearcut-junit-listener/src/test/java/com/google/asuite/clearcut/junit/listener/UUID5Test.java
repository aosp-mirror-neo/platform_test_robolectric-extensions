/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.asuite.clearcut.junit.listener;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class UUID5Test {

    @Test
    public void testUUID5() {
        String rexhoffman_google_com_uuid5 = "f543c3fc-651a-57a4-8d4f-f7f0961d2179";
        assertThat(UUID5
                .uuidOf(UUID5.NAMESPACE_DNS, "rexhoffman@google.com").toString())
                .isEqualTo(rexhoffman_google_com_uuid5);
    }
}
