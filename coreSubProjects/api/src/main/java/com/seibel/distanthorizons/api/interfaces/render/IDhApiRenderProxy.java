/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.api.interfaces.render;

import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.objects.DhApiResult;


/**
 * Used to interact with Distant Horizons' rendering system.
 *
 * @author James Seibel
 * @version 2024-7-27
 * @since API 1.0.0
 */
public interface IDhApiRenderProxy
{
	/**
	 * Forces any cached render data to be deleted and regenerated.
	 * This is generally called whenever resource packs are changed or specific
	 * rendering settings are changed in Distant Horizon's config. <Br><Br>
	 *
	 * If this is called on a dedicated server it won't do anything and will return {@link DhApiResult#success} = false <Br><Br>
	 *
	 * Background: <Br>
	 * When rendering Distant Horizons bakes each block's color into the geometry that's rendered. <Br>
	 * This improves rendering speed and VRAM size, but prevents dynamically changing LOD colors. <Br>
	 */
	DhApiResult<Boolean> clearRenderDataCache();
	
	/**
	 * Returns which specific {@link EDhApiRenderingApi}
	 * Distant Horizons will use for rendering. <br><br>
	 * 
	 * @throws IllegalStateException if no renderer has been bound yet, 
	 *      wait till after {@link DhApiAfterDhInitEvent} has been fired
	 * 
	 * @see DhApiAfterDhInitEvent
	 * @since API 7.0.0
	 */
	EDhApiRenderingApi getRenderingApi() throws IllegalStateException;
	/**
	 * Returns true if the current renderer
	 * is calling the base rendering API's method calls. <br>
	 * ie GL.drawArrays() for OpenGL. <Br><br>
	 *
	 * If DH is using a rendering interpretation layer like Blaze3D (Mojang's rendering API) 
	 * this will return false.
	 *
	 * @throws IllegalStateException if no renderer has been bound yet, 
	 *      wait till after {@link DhApiAfterDhInitEvent} has been fired
	 *
	 * @see DhApiAfterDhInitEvent
	 * @since API 7.0.0
	 */
	boolean isNativeRenderer() throws IllegalStateException;
	
	
	
	//=======================//
	// OpenGL object getters //
	//=======================//
	
	/**
	 * Returns the OpenGL name of Distant Horizons' depth texture. <br>
	 * Will return {@link DhApiResult#success} = false and {@link DhApiResult#payload} = -1 if the texture hasn't been created yet
	 * or a rendering API other than OpenGL is in use.
	 */
	DhApiResult<Integer> getDhDepthTextureId();
	
	/**
	 * Returns the OpenGL name of Distant Horizons' color texture. <br>
	 * Will return {@link DhApiResult#success} = false and {@link DhApiResult#payload} = -1 if the texture hasn't been created yet
	 * or a rendering API other than OpenGL is in use
	 */
	DhApiResult<Integer> getDhColorTextureId();
	
	/**
	 * Returns the OpenGL name of Distant Horizons' block color-ratio atlas. <br>
	 * Returns an unsuccessful result with payload -1 before the atlas is created
	 * or when the active renderer does not provide an OpenGL atlas. <br>
	 * Query this each render pass: growing the atlas can replace its texture ID.
	 * A gray texel (128) preserves the LOD's base color.
	 *
	 * @apiNote Backported from API 7.1.0 for Iris compatibility in the Tellus fork.
	 */
	default DhApiResult<Integer> getDhBlockRatioAtlasTextureGlId()
	{
		return DhApiResult.createFail("DH's block ratio atlas texture is unavailable.", -1);
	}

	
	
	//======================//
	// Shader compatibility //
	//======================//
	
	/**
	 * If set to true DH won't render opaque and transparent LODs in the same pass.
	 * Instead, opaque objects will be rendered at the normal time, but 
	 * transparent objects will only be rendered in a second pass during Minecraft's
	 * own transparent rendering pass.
	 */
	void setDeferTransparentRendering(boolean deferTransparentRendering);
	/** @return If DH should defer transparent rendering or not. */
	boolean getDeferTransparentRendering();
	
	/** This may change based on FOV, player speed, and other factors. */
	float getNearClipPlaneDistanceInBlocks(float partialTicks);
	
	
}
