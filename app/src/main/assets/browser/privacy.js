(() => {
  'use strict';
  // Best-effort page-level reduction only; workers and rendering fingerprints remain outside this shim.
  const override = (object, key, value) => {
    try { Object.defineProperty(object, key, { configurable: true, get: () => value }); } catch (_) {}
  };
  override(Navigator.prototype, 'hardwareConcurrency', 4);
  if ('deviceMemory' in navigator) override(Navigator.prototype, 'deviceMemory', 4);
  override(Navigator.prototype, 'doNotTrack', '1');
  override(Navigator.prototype, 'globalPrivacyControl', true);
  for (const context of [globalThis.WebGLRenderingContext, globalThis.WebGL2RenderingContext]) {
    if (!context) continue;
    const p = context.prototype;
    const extension = p.getExtension;
    const extensions = p.getSupportedExtensions;
    const parameter = p.getParameter;
    p.getExtension = function(name) {
      if (String(name).toLowerCase() === 'webgl_debug_renderer_info') return null;
      return extension.apply(this, arguments);
    };
    p.getSupportedExtensions = function() {
      const names = extensions.apply(this, arguments);
      return names && names.filter(name => name.toLowerCase() !== 'webgl_debug_renderer_info');
    };
    p.getParameter = function(name) {
      if (name === 37445 || name === 37446) return null;
      return parameter.apply(this, arguments);
    };
  }
})();
