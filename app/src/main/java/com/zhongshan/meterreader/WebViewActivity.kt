private fun compileUltimateInjectionJs(
        tabName: String,
        keys: Array<String>,
        values: Array<String>,
        pumpIds: Array<String>
    ): String {
        val sb = StringBuilder()
        sb.append("(function() {")
        sb.append("  var tabs = document.querySelectorAll('li, a, button, div, span');")
        // 核心修复：使用 indexOf > -1 替代 === 进行模糊匹配，兼容带有“组”或“...”的标签文本
        sb.append("  for(var i=0; i<tabs.length; i++) { if(tabs[i].innerText && tabs[i].innerText.trim().indexOf('$tabName') > -1) { tabs[i].click(); break; } }")

        sb.append("  setTimeout(function() {")
        sb.append("    var data = [];")
        for (i in keys.indices) {
            sb.append("    data.push({ k: '${keys[i].escapeJs()}', v: '${values[i].escapeJs()}' });")
        }

        sb.append("    var index = 0; var filledCount = 0;")
        sb.append("    function fillNext() {")
        sb.append("      if(index >= data.length) { ")
        for (pumpId in pumpIds) {
            sb.append("var chk = document.getElementById('$pumpId') || document.querySelector('[value=\"$pumpId\"],[name=\"$pumpId\"]');")
            sb.append("if(chk && chk.type === 'checkbox') {")
            sb.append("  var chkDesc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'checked');")
            sb.append("  var nativeCheckSetter = chkDesc && chkDesc.set;")
            sb.append("  if(nativeCheckSetter) { nativeCheckSetter.call(chk, true); } else { chk.checked = true; }")
            sb.append("  chk.dispatchEvent(new Event('change', { bubbles: true }));")
            sb.append("  chk.dispatchEvent(new Event('click', { bubbles: true }));")
            sb.append("}")
        }
        sb.append("        AndroidBridge.onFillComplete(filledCount); return; }")
        sb.append("      var item = data[index]; var el = document.getElementById(item.k) || document.querySelector('[name=\"'+item.k+'\"]');")
        sb.append("      if(el) {")
        sb.append("        el.focus();")
        sb.append("        var desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');")
        sb.append("        var nativeSetter = desc && desc.set;")
        sb.append("        if(nativeSetter) { nativeSetter.call(el, item.v); } else { el.value = item.v; }")
        sb.append("        el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, inputType: 'insertText', data: item.v }));")
        sb.append("        el.dispatchEvent(new Event('change', { bubbles: true }));")
        sb.append("        el.blur(); filledCount++;")
        sb.append("      }")
        sb.append("      index++; setTimeout(fillNext, 200);")
        sb.append("    }")
        sb.append("    fillNext();")
        sb.append("  }, 400);")
        sb.append("})();")
        return sb.toString()
    }
