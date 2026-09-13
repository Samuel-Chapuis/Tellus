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

package com.seibel.distanthorizons.common.render.openGl.terrain;

import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import com.seibel.distanthorizons.core.render.AbstractBlockTextureAtlas;
import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;

/**
 * The GPU side of the {@link BlockTextureRegistry},
 * a texture array with one layer per block face tile. <br><br>
 *
 * Layer {@link BlockTextureRegistry#UNTEXTURED_ID} is a uniform 1.0 color multiplier
 * so vertices without a texture render exactly like flat-colored LODs.
 *
 * @see BlockTextureRegistry
 */
public class GlBlockTextureAtlas extends AbstractBlockTextureAtlas
{
	public static final GlBlockTextureAtlas INSTANCE = new GlBlockTextureAtlas();
	
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	/** the texture unit the atlas is bound to while LODs render, the lightmap uses unit 0 */
	public static final int GL_BOUND_INDEX = 1;
	
	
	private final PixelUploadGlState uploadGlState = new PixelUploadGlState();
	
	private int textureId = 0;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private GlBlockTextureAtlas() {}
	
	//endregion
	
	
	
	//==================//
	// texture handling //
	//==================//
	//region
	
	@Override 
	protected void tryCreateOrResize(int width, int height)
	{
		int previousTexture = GL32.glGetInteger(GL32.GL_TEXTURE_BINDING_2D);
		if (previousTexture == this.textureId)
		{
			// Do not restore the name of a texture that is about to be deleted.
			previousTexture = 0;
		}
		DhApiRenderProxy.activeOpenGlDhBlockRatioAtlasTextureId = -1;
		try
		{
			if (this.textureId != 0)
			{
				GL32.glDeleteTextures(this.textureId);
			}
		
			this.textureId = GL32.glGenTextures();
			GL32.glBindTexture(GL32.GL_TEXTURE_2D, this.textureId);
			GL32.glTexImage2D(
				GL32.GL_TEXTURE_2D, 0,
				GL32.GL_RGBA8,
				width, height,
				0,
				GL32.GL_RGBA, GL32.GL_UNSIGNED_BYTE,
				(ByteBuffer) null
			);
		
			// nearest filtering keeps the blocky look and prevents
			// texels bleeding between unrelated tiles on adjacent layers
			GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_MIN_FILTER, GL32.GL_NEAREST);
			GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_MAG_FILTER, GL32.GL_NEAREST);
			GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_WRAP_S, GL32.GL_REPEAT);
			GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_WRAP_T, GL32.GL_REPEAT);
			GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_MAX_LEVEL, 0);
			DhApiRenderProxy.activeOpenGlDhBlockRatioAtlasTextureId = this.textureId;
		}
		finally
		{
			// Allocation must not replace the lightmap or an Iris sampler binding.
			GL32.glBindTexture(GL32.GL_TEXTURE_2D, previousTexture);
		}
	}
	
	//endregion
	
	
	
	//=========//
	// binding //
	//=========//
	//region
	
	public void bind()
	{
		GL32.glActiveTexture(GL32.GL_TEXTURE0 + GL_BOUND_INDEX);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, this.textureId);
		GL32.glActiveTexture(GL32.GL_TEXTURE0);
	}
	
	public void unbind()
	{
		GL32.glActiveTexture(GL32.GL_TEXTURE0 + GL_BOUND_INDEX);
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, 0);
		GL32.glActiveTexture(GL32.GL_TEXTURE0);
	}
	
	//endregion
	
	
	
	//===========//
	// uploading //
	//===========//
	//region
	
	@Override 
	protected void beforeWriteToTexture()
	{
		uploadGlState.saveState();
		
		GL32.glBindTexture(GL32.GL_TEXTURE_2D, this.textureId);
		
		GL32.glPixelStorei(GL32.GL_UNPACK_ROW_LENGTH, BlockTextureRegistry.TILE_HEIGHT_AND_WIDTH);
		GL32.glPixelStorei(GL32.GL_UNPACK_SKIP_PIXELS, 0);
		GL32.glPixelStorei(GL32.GL_UNPACK_SKIP_ROWS, 0);
		GL32.glPixelStorei(GL32.GL_UNPACK_ALIGNMENT, 1);
	}
	
	@Override 
	protected void writeToTexture(ByteBuffer pixelBuffer, int destinationX, int destinationY, int tileWidth, int tileHeight)
	{
		GL32.glTexSubImage2D(
			GL32.GL_TEXTURE_2D, 0,
			destinationX, destinationY,
			tileWidth, tileHeight,
			GL32.GL_RGBA, GL32.GL_UNSIGNED_BYTE, pixelBuffer
		);
	}
	
	@Override
	protected void afterWriteToTexture() { uploadGlState.close(); }
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	private static class PixelUploadGlState implements AutoCloseable
	{
		private int unpackRowLength = 0;
		private int unpackSkipPixels = 0;
		private int unpackSkipRows = 0;
		private int unpackAlignment = 0;
		
		private int textureBinding = 0;
		
		
		
		public void saveState()
		{
			this.unpackRowLength = GL32.glGetInteger(GL32.GL_UNPACK_ROW_LENGTH);
			this.unpackSkipPixels = GL32.glGetInteger(GL32.GL_UNPACK_SKIP_PIXELS);
			this.unpackSkipRows = GL32.glGetInteger(GL32.GL_UNPACK_SKIP_ROWS);
			this.unpackAlignment = GL32.glGetInteger(GL32.GL_UNPACK_ALIGNMENT);
			
			GLMC.glActiveTexture(GL32.GL_TEXTURE0);
			this.textureBinding = GL32.glGetInteger(GL32.GL_TEXTURE_BINDING_2D);
		}
		
		@Override 
		public void close()
		{
			GL32.glBindTexture(GL32.GL_TEXTURE_2D, this.textureBinding);
			
			GL32.glPixelStorei(GL32.GL_UNPACK_ROW_LENGTH, this.unpackRowLength);
			GL32.glPixelStorei(GL32.GL_UNPACK_SKIP_PIXELS, this.unpackSkipPixels);
			GL32.glPixelStorei(GL32.GL_UNPACK_SKIP_ROWS, this.unpackSkipRows);
			GL32.glPixelStorei(GL32.GL_UNPACK_ALIGNMENT, this.unpackAlignment);
		}
		
	}
	
	//endregion
	
	
	
}
