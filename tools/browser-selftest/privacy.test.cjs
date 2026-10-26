const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const script = fs.readFileSync('app/src/main/assets/browser/privacy.js', 'utf8');
class Navigator {}
Object.defineProperty(Navigator.prototype, 'deviceMemory', { configurable: true, get: () => 12 });
Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', { configurable: true, get: () => 8 });
class WebGLRenderingContext {
  getExtension(name) { return { name }; }
  getSupportedExtensions() { return ['WEBGL_debug_renderer_info', 'OES_texture_float']; }
  getParameter(name) { return name === 37446 ? 'REAL_GPU' : 123; }
}
class WebGL2RenderingContext extends WebGLRenderingContext {}
const env = { Navigator, navigator: new Navigator(), WebGLRenderingContext, WebGL2RenderingContext };
vm.createContext(env); vm.runInContext(script, env);
assert.equal(env.navigator.hardwareConcurrency, 4);
assert.equal(env.navigator.deviceMemory, 4);
assert.equal(env.navigator.globalPrivacyControl, true);
assert.equal(env.navigator.doNotTrack, '1');
assert.equal('webdriver' in env.navigator, false);
for (const Type of [WebGLRenderingContext, WebGL2RenderingContext]) {
  const gl = new Type();
  assert.equal(gl.getExtension('WEBGL_debug_renderer_info'), null);
  assert.equal(gl.getParameter(37446), null);
  assert.equal(gl.getParameter(123), 123);
  assert.deepEqual(Array.from(gl.getSupportedExtensions()), ['OES_texture_float']);
  assert.equal(gl.getExtension('OES_texture_float').name, 'OES_texture_float');
}
vm.runInContext(script, env);
assert.equal(new WebGLRenderingContext().getParameter(123), 123);
const reduced = { Navigator: class {}, navigator: {} };
vm.createContext(reduced); vm.runInContext(script, reduced);
assert.equal('deviceMemory' in reduced.Navigator.prototype, false);
console.log('PASS 17 page privacy assertions; no GPU API and repeated injection also handled');
