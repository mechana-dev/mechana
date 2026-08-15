/*
 * Copyright (c) 2026 Mark Vita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.mechana.plugins.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ImpulseResponseShaperTest {
	@Test
	void neutralOptionsBypassAndPreserveExactSamples() {
		var source = new ImpulseResponse(1_000, new double[][]{{1, 0.5, -0.25}});
		ImpulseResponse shaped = ImpulseResponseShaper.shape(source, new ImpulseResponseShaper.Options(1, 1, 0, 100));
		assertSame(source, shaped);
		assertArrayEquals(source.channel(0), shaped.channel(0));
	}

	@Test
	void earlyLateAttackAndDecayShapeOnlyExistingResponse() {
		double[] samples = new double[200];
		Arrays.fill(samples, 1);
		ImpulseResponse shaped = ImpulseResponseShaper.shape(new ImpulseResponse(1_000, new double[][]{samples}),
				new ImpulseResponseShaper.Options(0.5, 0.25, 20, 50));

		assertEquals(100, shaped.length());
		assertEquals(0, shaped.channel(0)[0]);
		assertEquals(0.5, shaped.channel(0)[30], 1e-9);
		assertTrue(shaped.channel(0)[90] < 0.1);
		assertEquals(0, shaped.channel(0)[99], 1e-9);
	}
}
