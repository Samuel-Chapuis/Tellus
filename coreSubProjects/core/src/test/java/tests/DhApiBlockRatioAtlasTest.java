/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 */

package tests;

import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import org.junit.Assert;
import org.junit.Test;

public class DhApiBlockRatioAtlasTest
{
	@Test
	public void irisInterfaceCallTracksAtlasCreationReplacementAndInvalidation()
	{
		IDhApiRenderProxy proxy = DhApiRenderProxy.INSTANCE;
		int previousAtlas = DhApiRenderProxy.activeOpenGlDhBlockRatioAtlasTextureId;
		try
		{
			DhApiRenderProxy.activeOpenGlDhBlockRatioAtlasTextureId = -1;
			assertUnavailable(proxy.getDhBlockRatioAtlasTextureGlId());
			DhApiRenderProxy.activeOpenGlDhBlockRatioAtlasTextureId = 0;
			assertUnavailable(proxy.getDhBlockRatioAtlasTextureGlId());

			// Call through the public interface, just as the precompiled Iris jar does.
			DhApiRenderProxy.activeOpenGlDhBlockRatioAtlasTextureId = 37;
			assertTexture(37, proxy.getDhBlockRatioAtlasTextureGlId());
			DhApiRenderProxy.activeOpenGlDhBlockRatioAtlasTextureId = 92;
			assertTexture(92, proxy.getDhBlockRatioAtlasTextureGlId());
			DhApiRenderProxy.activeOpenGlDhBlockRatioAtlasTextureId = -1;
			assertUnavailable(proxy.getDhBlockRatioAtlasTextureGlId());
		}
		finally
		{
			DhApiRenderProxy.activeOpenGlDhBlockRatioAtlasTextureId = previousAtlas;
		}
	}

	@Test
	public void atlasDoesNotReplaceTheLegacyColorOrDepthTextures()
	{
		IDhApiRenderProxy proxy = DhApiRenderProxy.INSTANCE;
		int previousAtlas = DhApiRenderProxy.activeOpenGlDhBlockRatioAtlasTextureId;
		int previousColor = DhApiRenderProxy.activeOpenGlDhColorTextureId;
		int previousDepth = DhApiRenderProxy.activeOpenGlDhDepthTextureId;
		try
		{
			DhApiRenderProxy.activeOpenGlDhBlockRatioAtlasTextureId = 37;
			DhApiRenderProxy.activeOpenGlDhColorTextureId = 38;
			DhApiRenderProxy.activeOpenGlDhDepthTextureId = 39;
			assertTexture(37, proxy.getDhBlockRatioAtlasTextureGlId());
			assertTexture(38, proxy.getDhColorTextureId());
			assertTexture(39, proxy.getDhDepthTextureId());
		}
		finally
		{
			DhApiRenderProxy.activeOpenGlDhBlockRatioAtlasTextureId = previousAtlas;
			DhApiRenderProxy.activeOpenGlDhColorTextureId = previousColor;
			DhApiRenderProxy.activeOpenGlDhDepthTextureId = previousDepth;
		}
	}

	@Test
	public void implementationsOfTheOlderInterfaceRemainUsable()
	{
		IDhApiRenderProxy legacyProxy = new IDhApiRenderProxy()
		{
			@Override public DhApiResult<Boolean> clearRenderDataCache() { return DhApiResult.createSuccess(); }
			@Override public EDhApiRenderingApi getRenderingApi() { return EDhApiRenderingApi.OPEN_GL; }
			@Override public boolean isNativeRenderer() { return true; }
			@Override public DhApiResult<Integer> getDhDepthTextureId() { return DhApiResult.createSuccess(12); }
			@Override public DhApiResult<Integer> getDhColorTextureId() { return DhApiResult.createSuccess(13); }
			@Override public void setDeferTransparentRendering(boolean defer) { }
			@Override public boolean getDeferTransparentRendering() { return false; }
			@Override public float getNearClipPlaneDistanceInBlocks(float partialTicks) { return 16; }
		};
		assertUnavailable(legacyProxy.getDhBlockRatioAtlasTextureGlId());
		assertTexture(12, legacyProxy.getDhDepthTextureId());
		assertTexture(13, legacyProxy.getDhColorTextureId());
	}

	private static void assertUnavailable(DhApiResult<Integer> result)
	{
		Assert.assertFalse(result.success);
		Assert.assertEquals(Integer.valueOf(-1), result.payload);
	}

	private static void assertTexture(int expected, DhApiResult<Integer> result)
	{
		Assert.assertTrue(result.message, result.success);
		Assert.assertEquals(Integer.valueOf(expected), result.payload);
	}
}
